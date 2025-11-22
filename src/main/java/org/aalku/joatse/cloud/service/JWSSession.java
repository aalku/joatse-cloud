package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.tools.io.BandwithCalculator.OneWayTraffic;
import org.aalku.joatse.cloud.tools.io.BandwithCalculator.TwoWayBandwithCalculator;
import org.aalku.joatse.cloud.tools.io.BandwithLimiter;
import org.aalku.joatse.cloud.tools.io.IOTools;
import org.aalku.joatse.cloud.tools.io.WebSocketSendWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

public class JWSSession {
	
	private Logger log = LoggerFactory.getLogger(JWSSession.class);

	private ReentrantLock lock = new ReentrantLock();
	private Map<Long, AbstractToSocketConnection> socketConnectionMap = new LinkedHashMap<>();
	private WebSocketSendWorker wsSendWorker;

	/**
	 * Method to close the WebSocket session.
	 * 
	 * I use this so the session is not directly referenced and I am tempted to do
	 * other things with it
	 */
	private BiConsumer<String, Throwable> closer;

	private SharedResourceLot sharedResourceLot;
	
	private BandwithLimiter bandwithLimiter;

	private BandwithLimitManager bandwithLimitManager;
	
	private TwoWayBandwithCalculator bandwithCalculator = new TwoWayBandwithCalculator();

	private final SessionPingHandler sessionPingHandler = new SessionPingHandler();

	private CompletableFuture<byte[]> targetPublicKey = null;

	/** Ping stats and sending, but it's scheduled from the outside */
	public class SessionPingHandler {
		private AtomicLong lastPingNanoTime = new AtomicLong(0);
		private AtomicLong lastPongNanoTime = new AtomicLong(0);
		
		public long getLastPingAgoSeconds() {
			long last = lastPingNanoTime.get();
			if (last == 0) {
				return Integer.MAX_VALUE;
			} else {
				return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - last);
			}
		}

		public long getLastPongAgoSeconds() {
			long last = lastPongNanoTime.get();
			if (last == 0) {
				return Integer.MAX_VALUE;
			} else {
				return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - last);
			}
		}

		public void sendPing() {
//			log.info("Sending ping to " + getTunnelUUID());
			wsSendWorker.sendMessage(new PingMessage());
			lastPingNanoTime.set(System.nanoTime());

//			// Nothing to do with the ping
//			long bpsIn = bandwithCalculator.getTrafficIn().getBps();
//			long bpsOut = bandwithCalculator.getTrafficOut().getBps();
//			log.info("Bandwith of session {} = {}/{}", getTunnelUUID(), bpsIn/1024.0, bpsOut/1024.0);
		}

		public void receivedPong() {
//			log.info("Received pong from " + getTunnelUUID());
			lastPongNanoTime.set(System.nanoTime());
		}
	}
	

	public JWSSession(WebSocketSession session, BandwithLimitManager bandwithLimitManager) {
		this.bandwithLimitManager = bandwithLimitManager;
		this.wsSendWorker = new WebSocketSendWorker(session);
		this.setBandwithLimiter(bandwithLimitManager.getGlobalBandwithLimiter());
		this.closer = (BiConsumer<String, Throwable>)(closeReason, e)->{
			log.error("Session is being closed. Reason={}, Error={}", closeReason, e, e);
			lock.lock();
			try {
				if(session.isOpen()) {
					/*
					 * If it has a reason then someone else wanted to close it before, and we want
					 * to keep the first reason.
					 */
					session.getAttributes().computeIfAbsent(JoatseWsHandler.SESSION_KEY_CLOSE_REASON, k->closeReason);
				} else {
					session.getAttributes().computeIfAbsent(JoatseWsHandler.SESSION_KEY_CLOSE_REASON, k->"Wanted to close it but was already closed");
				}
				/*
				 * We need a copy since c.close() will update the map and we cannot iterate the
				 * map at the same time.
				 */
				ArrayList<AbstractToSocketConnection> copy = new ArrayList<>(socketConnectionMap.values());
				for (AbstractToSocketConnection c: copy) {
					lock.unlock(); // Unlock while closing it so we don't share the lock with anyone else
					try {
						c.close(e, false);
					} finally {
						lock.lock();
					}
				}
				IOTools.runFailable(()->this.wsSendWorker.close());
				IOTools.runFailable(()->session.close());
			} finally {
				lock.unlock();
			}
		};
	}

	private void setBandwithLimiter(BandwithLimiter bandwithLimiter) {
		this.bandwithLimiter = bandwithLimiter;
		this.wsSendWorker.setBandwithLimiter(bandwithLimiter);
		this.wsSendWorker.setBandwithCalculator(bandwithCalculator.getOneWayOut());
	}

	public void addTunnelConnection(AbstractToSocketConnection c) {
		lock.lock();
		try {
			socketConnectionMap.put(c.socketId, c);
		} finally {
			lock.unlock();
		}
	}

	public void close(String reason) {
		close(reason, null);
	}
	
	public void close(String reason, Throwable e) {
		Optional.ofNullable(targetPublicKey)
				.ifPresent(x -> x.completeExceptionally(new IOException("Closing session", e)));
		closer.accept(reason, e);
	}

	public void remove(AbstractToSocketConnection abstractToSocketConnection) {
		lock.lock();
		try {
			socketConnectionMap.remove(abstractToSocketConnection.socketId, abstractToSocketConnection);
			abstractToSocketConnection.assertClosed();
		} finally {
			lock.unlock();
		}
	}

	public void handleBinaryMessage(BinaryMessage message) throws IOException {
		ByteBuffer buffer = message.getPayload();
		int bytes = message.getPayloadLength();
		try {
			int version = buffer.get();
			if (version != TunnelTcpConnection.PROTOCOL_VERSION) {
				throw new IOException("Unsupported BinaryMessage protocol version: " + version);
			}
			byte type = buffer.get();
			if (type == AbstractToSocketConnection.MESSAGE_PUBLIC_KEY) {
				byte[] key = new byte[buffer.remaining()];
				buffer.get(key);
				lock.lock();
				try {
					if (targetPublicKey == null) {
						throw new IllegalStateException("targetPublicKey received unexpectedly");
					} else if (targetPublicKey.isDone() && !targetPublicKey.isCompletedExceptionally()) {
						throw new IllegalStateException("targetPublicKey received twice or unexpectedly");
					}
					targetPublicKey.complete(key);
				} finally {
					lock.unlock();
				}
			} else if (AbstractToSocketConnection.messageTypesHandled.contains(type)) {
				long socketId = buffer.getLong();
				Runnable runWithoutLock = null; 
				AbstractToSocketConnection c = null;
				lock.lock();
				try {
					c = socketConnectionMap.get(socketId);
					if (c == null) {
						log.warn("Received ws data relating a tcp connection that was disconnected: {}", socketId);
						return;
					}
					runWithoutLock = c.receivedMessageFromTarget(buffer, type); // blocking with lock is ok
				} catch (Exception e) {
					if (c != null) {
						log.warn("Error handling tcp data: " + e, e);
						c.close();
					}
				} finally {
					lock.unlock();
				}
				if (runWithoutLock != null) {
					runWithoutLock.run();
				}
			} else {
				throw new IOException("Unsupported BinaryMessage message type: " + type);
			}
		} finally {
			bandwithLimiter.next(bytes).sleep();
			bandwithCalculator.reportPacketIn(bytes);
		}
	}

	public CompletableFuture<Void> sendMessage(WebSocketMessage<?> message) {
		return wsSendWorker.sendMessage(message);
	}

	public SharedResourceLot getSharedResourceLot() {
		lock.lock();
		try {
			return sharedResourceLot;
		} finally {
			lock.unlock();
		}
	}

	public void setSharedResourceLot(SharedResourceLot sharedResourceLot) {
		lock.lock();
		try {
			this.sharedResourceLot = sharedResourceLot;
			this.setBandwithLimiter(bandwithLimitManager.getUserBandwithLimiter(sharedResourceLot.getOwner().getUuid()));
		} finally {
			lock.unlock();
		}
	}

	public UUID getTunnelUUID() {
		lock.lock();
		try {
			return Optional.ofNullable(sharedResourceLot).map(t->t.getUuid()).orElse(null);
		} finally {
			lock.unlock();
		}
	}

	public SessionPingHandler getSessionPingHandler() {
		return sessionPingHandler;
	}

	public OneWayTraffic getTrafficIn() {
		return bandwithCalculator.getTrafficIn();
	}

	public OneWayTraffic getTrafficOut() {
		return bandwithCalculator.getTrafficOut();
	}

	public CompletableFuture<byte[]> getTargetPublicKey() {
		lock.lock();
		try {
			if (targetPublicKey == null) {
				targetPublicKey = new CompletableFuture<byte[]>();
				ByteBuffer buffer = ByteBuffer.allocate(2);
				buffer.put(AbstractToSocketConnection.PROTOCOL_VERSION);
				buffer.put(AbstractToSocketConnection.MESSAGE_PUBLIC_KEY);
				buffer.flip();
				sendMessage(new BinaryMessage(buffer)).exceptionally(e->{
					targetPublicKey.completeExceptionally(new IOException("Can't send message asking for public key", e));
					return null;
				});
			}
			return targetPublicKey.orTimeout(1, TimeUnit.MINUTES);
		} finally {
			lock.unlock();
		}
	}
	
	

}
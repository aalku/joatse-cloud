package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.aalku.joatse.cloud.tools.io.IOTools;
import org.aalku.joatse.cloud.tools.io.WebSocketSendWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

public class JoatseSession {

	private Logger log = LoggerFactory.getLogger(JoatseSession.class);

	private ReentrantLock lock = new ReentrantLock();
	private Map<Long, TunnelTcpConnection> connectionMap = new LinkedHashMap<>();
	private WebSocketSendWorker wsSendWorker;

	/**
	 * Method to close the WebSocket session.
	 * 
	 * I use this so the session is not directly referenced and I am tempted to do
	 * other things with it
	 */
	private Consumer<String> closer;

	public JoatseSession(WebSocketSession session) {
		wsSendWorker = new WebSocketSendWorker(session);
		this.closer = (Consumer<String>)(closeReason)->{
			synchronized (this.closer) {
				if(session.isOpen()) {
					/*
					 * If it has a reason then someone else wanted to close it before, and we want
					 * to keep the first reason.
					 */
					session.getAttributes().computeIfAbsent(JoatseWsHandler.SESSION_KEY_CLOSE_REASON, k->closeReason);
				} else {
					session.getAttributes().computeIfAbsent(JoatseWsHandler.SESSION_KEY_CLOSE_REASON, k->"Wanted to close it but was already closed");
				}
				IOTools.runFailable(()->this.wsSendWorker.close());
				IOTools.runFailable(()->session.close());
			}
		};
	}

	void add(TunnelTcpConnection c) {
		lock.lock();
		try {
			connectionMap.put(c.socketId, c);
		} finally {
			lock.unlock();
		}
	}

	public void close(String reason) {
		close(reason, null);
	}
	
	public void close(String reason, Throwable e) {
		lock.lock();
		try {
			/*
			 * We need a copy since c.close() will update the map and we cannot iterate the
			 * map at the same time.
			 */
			ArrayList<TunnelTcpConnection> copy = new ArrayList<TunnelTcpConnection>(connectionMap.values());
			for (TunnelTcpConnection c: copy) {
				lock.unlock(); // Unlock while closing it so we don't share the lock with anyone else
				try {
					c.close(e, false);
				} finally {
					lock.lock();
				}
			}
			wsSendWorker.close();
		} finally {
			lock.unlock();
		}
	}

	void remove(TunnelTcpConnection c) {
		lock.lock();
		try {
			connectionMap.remove(c.socketId, c);
			c.assertClosed();
		} finally {
			lock.unlock();
		}
	}

	public void handleBinaryMessage(BinaryMessage message) throws IOException {
		ByteBuffer buffer = message.getPayload();
		int version = buffer.get();
		if (version != TunnelTcpConnection.PROTOCOL_VERSION) {
			throw new IOException("Unsupported BinaryMessage protocol version: " + version);
		}
		byte type = buffer.get();
		if (TunnelTcpConnection.messageTypesHandled.contains(type)) {
			long socketId = buffer.getLong();
			Runnable runWithoutLock = null; 
			TunnelTcpConnection c = null;
			lock.lock();
			try {
				c = connectionMap.get(socketId);
				if (c == null) {
					log.warn("Received ws data relating a tcp connection that was disconnected: {}", socketId);
				}
				runWithoutLock = c.receivedMessage(buffer, type); // blocking with lock is ok
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
	}

	public CompletableFuture<Void> sendMessage(WebSocketMessage<?> message) {
		return wsSendWorker.sendMessage(message);
	}

}
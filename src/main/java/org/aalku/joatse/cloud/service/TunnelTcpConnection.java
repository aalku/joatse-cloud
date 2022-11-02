package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.zip.CRC32;

import org.aalku.joatse.cloud.tools.io.IOTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;

public class TunnelTcpConnection {

	static final byte PROTOCOL_VERSION = 1;
	
	private static final byte MESSAGE_TYPE_NEW_TCP_SOCKET = 1;
	private static final byte MESSAGE_TCP_DATA = 2;
	static final byte MESSAGE_TCP_CLOSE = 3;
	
	public static final Set<Byte> messageTypesHandled = new HashSet<>(Arrays.asList(MESSAGE_TYPE_NEW_TCP_SOCKET, MESSAGE_TCP_DATA, MESSAGE_TCP_CLOSE));
	
	private static final int MAX_HEADER_SIZE_BYTES = 50;
	private static final int DATA_BUFFER_SIZE = 1024 * 63;

	private final Logger log = LoggerFactory.getLogger(TunnelTcpConnection.class);

	private final JWSSession jSession;
	private final AsynchronousSocketChannel tcp;
	/** Socket instance random id */
	public final long socketId = new Random().nextLong() & Long.MAX_VALUE;
	public final long targetId;
	private final CRC32 dataCRCT2W = new CRC32();
	private final CRC32 dataCRCW2T = new CRC32();	
	private final CompletableFuture<Boolean> closeStatus = new CompletableFuture<>();
	private final CompletableFuture<Void> connectionToFinalTargetResult = new CompletableFuture<Void>();

	public TunnelTcpConnection(JWSSession jSession, AsynchronousSocketChannel tcp, long targetId) {
		this.jSession = jSession;
		this.tcp = tcp;
		this.targetId = targetId;
		this.closeStatus.whenComplete((r,e)->jSession.remove(this));
		jSession.addTunnelConnection(this);

		ByteBuffer buffer = ByteBuffer.allocate(MAX_HEADER_SIZE_BYTES + DATA_BUFFER_SIZE);
		
		sendNewSocketMessage(buffer, targetId) // Send header, then ...
			.thenCompose((Void x)->connectionToFinalTargetResult.exceptionally(e->{
				log.error("Error connecting to to final target: " + e);
				throw new RuntimeException("Error connecting to to final target: " + e, e);
			}))
			.thenAccept(x->{
					tcpToWs(buffer); // start copying from WS to TCP
				}).exceptionally(e -> {
					close(e, false);
					return null;
				});
	}
	
	private CompletableFuture<Void> sendMessage(WebSocketMessage<?> message) {
		return jSession.sendMessage(message);
	}


	public void receivedWsTcpClose() {
		this.close(null, true);
	}

	public void notifyFinalTargetConnected(boolean connected) {
		if (connected) {
			connectionToFinalTargetResult.complete(null);
		} else {
			connectionToFinalTargetResult.completeExceptionally(new IOException("Can't connect to final targetId: " + targetId));
		}
	}

	/**
	 * 
	 * @param buffer with position pointing to tcp data
	 * @param crc32 of packet
	 * @throws IOException 
	 */
	private void receivedWsTcpMessage(ByteBuffer buffer, long crc32Field) throws IOException {
		buffer.mark();
		dataCRCW2T.update(buffer);
		if (dataCRCW2T.getValue() != crc32Field) {
			throw new IOException("CRC32 error. Expected " + Long.toHexString(crc32Field) + " but calc was " + Long.toHexString(dataCRCW2T.getValue()));
		}
		// log.info("crc is OK: {}", Long.toHexString(crc32Field));
		buffer.reset();
		try {
			tcpWrite(buffer).get(); // This is blocking to ensure write order. TODO prepare an async version
		} catch (InterruptedException | ExecutionException e) {
			throw new IOException("Error writting to tcp: " + e, e);
		}
	}
	
	/**
	 * Recursively writes all the buffer to tcp.
	 */
	private CompletableFuture<Integer> tcpWrite(ByteBuffer buffer) {
		CompletableFuture<Integer> res = new CompletableFuture<Integer>();
		AsynchronousSocketChannel channel = tcp;
		channel.write(buffer, null, new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				// log.info("written to tcp: {}", IOTools.toString(buffer, p, result));
				if (buffer.hasRemaining()) {
					tcpWrite(buffer) // Recursively write the rest
					.thenAccept(n -> res.complete(n + result)) // Then complete with all the written
					.exceptionally(e -> { // Or fail
						res.completeExceptionally(e);
						return null;
					});
				} else {
					res.complete(result);
				}
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				if (exc instanceof AsynchronousCloseException) {
					log.error("tcp write fail because the socket was closed");
					close(null, true);
					res.complete(0);
				} else {
					res.completeExceptionally(exc);
				}
			}
		});
		return res;
	}

	private CompletableFuture<Void> sendNewSocketMessage(ByteBuffer buffer, long targetId) {
		writeSocketHeader(buffer, MESSAGE_TYPE_NEW_TCP_SOCKET);
		buffer.putLong(targetId);
		buffer.flip();
		return sendMessage(new BinaryMessage(buffer, true));
	}

	private void tcpToWs(ByteBuffer buffer) {
		/*
		 * We save some space for the header when reading from TCP to the buffer
		 */
		final int headerLen = 14;
		buffer.clear();
		buffer.position(headerLen);
		tcpRead(buffer).thenAccept(bytesRead->{
			// Save buffer position (for flip)
			int pos = buffer.position();
			if (bytesRead < 0) {
				close();
				return;
			}
			if (pos - headerLen != bytesRead) {
				log.error("bytesRead assertion error. {} != {}", pos - headerLen, bytesRead);
				close(new AssertionError("bytesRead assertion error"), false);
			}
			// Write header at 0
			buffer.position(0);
			writeSocketHeader(buffer, MESSAGE_TCP_DATA);
			// Update CRC calc skipping the header
			dataCRCT2W.update(buffer.array(), buffer.arrayOffset() + headerLen, pos - headerLen);
			buffer.putInt((int) dataCRCT2W.getValue());
			if (buffer.position() != headerLen) {
				log.error("headerLen assertion error. {} != {}", buffer.position(), headerLen);
				close(new AssertionError("headerLen assertion error"), false);
			}
			// Back to position, and flip for reading
			buffer.position(pos);
			buffer.flip();
			sendMessage(new BinaryMessage(buffer, true)).whenCompleteAsync((x, e)->{
				if (e != null) {
					close(e, false);
				} else {
					// log.info("CRC32T2W = {}", Integer.toHexString((int)dataCRCT2W.getValue()) );
					tcpToWs(buffer);
				}
			});
		}).exceptionally(e->{
			close(e, false);
			return null;
		});
	}
	
	private int writeSocketHeader(ByteBuffer buffer, byte type) {
		buffer.clear();
		buffer.put(PROTOCOL_VERSION);
		buffer.put(type);
		buffer.putLong(socketId);
		return buffer.position();
	}

	private CompletableFuture<Integer> tcpRead(ByteBuffer readBuffer) {
		AsynchronousSocketChannel channel = this.tcp;
		CompletableFuture<Integer> res = new CompletableFuture<Integer>();
		channel.read(readBuffer, null, new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				res.complete(result);
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				if (exc instanceof AsynchronousCloseException) {
					close(null, true);
					res.complete(0);
				} else {
					res.completeExceptionally(exc);
				}
			}
		});
		return res;
	}

	public void close() {
		close(null, null);
	}

	void close(Throwable e, Boolean remote) {
		IOTools.runFailable(()->tcp.close());
		sendMessage(newTcpSocketCloseMessage()); // Tell WS
		jSession.remove(this);
		if (e == null) {
			closeStatus.complete(remote);
		} else {
			closeStatus.completeExceptionally(e);
		}
	}
	
	private WebSocketMessage<?> newTcpSocketCloseMessage() {
		ByteBuffer buffer = ByteBuffer.allocate(11);
		buffer.clear();
		buffer.put(PROTOCOL_VERSION);
		buffer.put(MESSAGE_TCP_CLOSE);
		buffer.putLong(socketId);
		buffer.flip();
		return new BinaryMessage(buffer);
	}

	public CompletableFuture<Boolean> getCloseStatus() {
		return closeStatus;
	}

	public void assertClosed() {
		if (tcp.isOpen()) { // Must be closed
			IOTools.runFailable(()->tcp.close());
			throw new AssertionError("TunnelTcpConnection.JoatseSession.remove(c) assertion error");
		}
	}

	public Runnable receivedMessage(ByteBuffer buffer, byte type) {
		if (type == MESSAGE_TCP_DATA) {
			try {
				long crc32Field = buffer.getInt() & 0xFFFFFFFFL;
				receivedWsTcpMessage(buffer, crc32Field);
				return null;
			} catch (Exception e) {
				log.warn("Error sending data to TCP: " + e, e);
				return ()->close(); // Call it without lock
			}
		} else if (type == MESSAGE_TYPE_NEW_TCP_SOCKET) {
			int res = buffer.get();
			return ()->notifyFinalTargetConnected(res == 1); // Notify without the lock
		} else if (type == TunnelTcpConnection.MESSAGE_TCP_CLOSE) {
			return ()->receivedWsTcpClose(); // Can be executed with lock I guess
		} else {
			throw new RuntimeException("Unsupported message type: " + type);
		}
	}
}

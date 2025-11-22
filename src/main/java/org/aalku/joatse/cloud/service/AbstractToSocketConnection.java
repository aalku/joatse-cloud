package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

import org.slf4j.Logger;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;

public abstract class AbstractToSocketConnection {
	
	protected static final byte PROTOCOL_VERSION = 1;
	
	protected static final byte MESSAGE_TYPE_NEW_SOCKET = 1;
	protected static final byte MESSAGE_SOCKET_DATA = 2;
	protected static final byte MESSAGE_SOCKET_CLOSE = 3;
	protected static final byte MESSAGE_PUBLIC_KEY = 4;
	// MESSAGE_FILE_READ_REQUEST (5) is deprecated - use MESSAGE_TYPE_NEW_SOCKET with additionalPayload instead
	
	public static final Set<Byte> messageTypesHandled = new HashSet<>(Arrays.asList(MESSAGE_TYPE_NEW_SOCKET, MESSAGE_SOCKET_DATA, MESSAGE_SOCKET_CLOSE));
	
	protected static final int MAX_HEADER_SIZE_BYTES = 50;
	protected static final int DATA_BUFFER_SIZE = 1024 * 63;

	/** Socket instance random id */
	public final long socketId = new Random().nextLong() & Long.MAX_VALUE;
	
	private final Queue<Runnable> sendQueue = new LinkedBlockingDeque<>();
	private final AtomicBoolean sending = new AtomicBoolean(false);
	protected final ReentrantLock sendLock = new ReentrantLock(true);
	
	protected final JWSSession jSession;
	
	protected final long targetId;
	private final CRC32 dataCRCT2W = new CRC32();
	private final CRC32 dataCRCW2T = new CRC32();	
	private final CompletableFuture<Boolean> closeStatus = new CompletableFuture<>();
	protected final CompletableFuture<Void> connectionToFinalTargetResult = new CompletableFuture<Void>();

	/**
	 * Protected constructor that initializes and registers the connection.
	 * Subclasses should pass additionalPayload to include in NEW_SOCKET message.
	 * 
	 * @param targetId The target ID for this connection
	 * @param jSession The WebSocket session
	 * @param additionalPayload Optional payload to send with NEW_SOCKET (e.g., file offset/length for file tunnels)
	 */
	protected AbstractToSocketConnection(long targetId, JWSSession jSession, ByteBuffer additionalPayload) {
		this.jSession = jSession;
		this.targetId = targetId;
		getCloseStatus().whenComplete((r,e)->jSession.remove(this));
		
		// Register with session BEFORE sending NEW_SOCKET so responses can be routed
		jSession.addTunnelConnection(this);
		
		// Send NEW_SOCKET message with optional payload
		initializeConnection(additionalPayload);
	}

	/**
	 * Initialize the connection by sending NEW_SOCKET message to the target.
	 * The NEW_SOCKET message includes:
	 * - Protocol version (1 byte)
	 * - Message type: NEW_SOCKET (1 byte)
	 * - Socket ID (8 bytes)
	 * - Target ID (8 bytes)
	 * - Additional payload (variable length, optional)
	 * 
	 * For file tunnels, additionalPayload contains:
	 * - Offset: 8 bytes (long) - starting position in file
	 * - Length: 8 bytes (long) - number of bytes to read (-1 for entire file)
	 * 
	 * For terminal tunnels, additionalPayload contains encrypted session data.
	 * For TCP/HTTP tunnels, additionalPayload is typically null.
	 * 
	 * @param additionalPayload Optional payload appended to NEW_SOCKET message
	 */
	private void initializeConnection(ByteBuffer additionalPayload) {
		sendLock.lock();
		try {
			ByteBuffer buffer = allocateHeaderAndDataBuffer();
			writeSocketHeader(buffer, MESSAGE_TYPE_NEW_SOCKET);
			buffer.putLong(targetId);
			if (additionalPayload != null) {
				buffer.put(additionalPayload);
			}
			buffer.flip();
			sendRawMessageToTarget(buffer) // Send header, then ...
				.thenCompose((Void x)->connectionToFinalTargetResult.exceptionally(e->{
					return errorConnectingToFinalTarget(e);
				}))
				.thenAccept(x->{
						copyFromClientToTargetForever();
					}).exceptionally(e -> {
						close(e, false);
						return null;
					});
		} finally {
			sendLock.unlock();
		}
	}

	protected ByteBuffer allocateHeaderAndDataBuffer() {
		return ByteBuffer.allocate(MAX_HEADER_SIZE_BYTES + DATA_BUFFER_SIZE);
	}

	protected ByteBuffer allocateDataBuffer() {
		return ByteBuffer.allocate(DATA_BUFFER_SIZE);
	}
	
	protected abstract void copyFromClientToTargetForever();

	protected abstract void closeInternal(Throwable e, Boolean b);
	
	protected abstract Logger getLog();
	
	public final void close(Throwable e, Boolean remote) {
		sendLock.lock();
		try {
			sendRawMessageToTarget(newTcpSocketCloseMessage()); // Tell WS
		} finally {
			sendLock.unlock();
		}
		closeInternal(e, remote);
		signalCloseStatus(e, remote);

	}

	protected abstract Void errorConnectingToFinalTarget(Throwable e);
	
	/**
	 * 
	 * @param buffer with position pointing to data
	 * @param crc32 of packet
	 * @throws IOException 
	 */
	protected void sendFromTargetToClient(ByteBuffer buffer, long crc32Field) throws IOException {
		buffer.mark();
		dataCRCW2T.update(buffer);
		if (dataCRCW2T.getValue() != crc32Field) {
			throw new IOException("CRC32 error. Expected " + Long.toHexString(crc32Field) + " but calc was " + Long.toHexString(dataCRCW2T.getValue()));
		}
		// log.info("crc is OK: {}", Long.toHexString(crc32Field));
		buffer.reset();
		try {
			writeToClient(buffer).get(); // This is blocking to ensure write order. TODO prepare an async version
		} catch (InterruptedException | ExecutionException e) {
			throw new IOException("Error writting to client: " + e, e);
		}
	}
	
	protected ByteBuffer newTcpSocketCloseMessage() {
		ByteBuffer buffer = ByteBuffer.allocate(11);
		buffer.clear();
		buffer.put(PROTOCOL_VERSION);
		buffer.put(MESSAGE_SOCKET_CLOSE);
		buffer.putLong(socketId);
		buffer.flip();
		return buffer;
	}
	
	/**
	 * 
	 * @param buffer First thing at buffer pos is crc. The header before should be ignored and the rest is payload
	 * @param type
	 * @return
	 */
	public Runnable receivedMessageFromTarget(ByteBuffer buffer, byte type) {
		if (type == MESSAGE_SOCKET_DATA) {
			try {
				long crc32Field = buffer.getInt() & 0xFFFFFFFFL;
				sendFromTargetToClient(buffer, crc32Field);
				return null;
			} catch (Exception e) {
				getLog().warn("Error sending data to client: " + e, e);
				return ()->close(); // Call it without lock
			}
		} else if (type == MESSAGE_TYPE_NEW_SOCKET) {
			int res = buffer.get();
			return ()->notifyFinalTargetConnected(res == 1); // Notify without the lock
		} else if (type == MESSAGE_SOCKET_CLOSE) {
			return ()->targetClosedSocket(); // Can be executed with lock I guess
		} else {
			throw new RuntimeException("Unsupported message type: " + type);
		}
	}

	public void notifyFinalTargetConnected(Boolean connected) {
		if (connected) {
			connectionToFinalTargetResult.complete(null);
		} else {
			connectionToFinalTargetResult.completeExceptionally(new IOException("Can't connect to final targetId: " + targetId));
		}
	}

	public void targetClosedSocket() {
		this.close(null, true);
	}

	public void close() {
		close(null, null);
	}

	protected int writeSocketHeader(ByteBuffer buffer, byte type) {
		buffer.clear();
		buffer.put(PROTOCOL_VERSION);
		buffer.put(type);
		buffer.putLong(socketId);
		return buffer.position();
	}


	/**
	 * Recursively writes all the buffer to client.
	 * @param buffer Everything from buffer pos is data
	 */
	protected abstract CompletableFuture<Integer> writeToClient(ByteBuffer buffer);

	protected CompletableFuture<Void> sendDataMessageToTarget(ByteBuffer payload) {
		sendLock.lock();
		try {
			int len = payload.remaining();
			ByteBuffer sendToTargetRawBuffer = ByteBuffer.allocate(payload.limit() + 14);
			int crcPos = writeSocketHeader(sendToTargetRawBuffer, MESSAGE_SOCKET_DATA);
			// Update CRC calc
			sendToTargetRawBuffer.putInt(updatedataCRCT2W(payload.array(), payload.arrayOffset() + payload.position(), payload.remaining()));
			sendToTargetRawBuffer.put(payload);
			if (sendToTargetRawBuffer.position() != crcPos + 4 + len) {
				getLog().error("Assertion error. {} != {}", sendToTargetRawBuffer.position(), crcPos + 4 + len);
				AssertionError e = new AssertionError("Assertion error of msg len and buffer pos");
				close(e, false);
				throw e;
			}
			sendToTargetRawBuffer.flip();
			return sendRawMessageToTarget(sendToTargetRawBuffer);
		} finally {
			sendLock.unlock();
		}
	} 

	protected CompletableFuture<Void> sendRawMessageToTarget(ByteBuffer buffer) {
		if (!sendLock.isHeldByCurrentThread()) {
			throw new AssertionError("!sendLock.isHeldByCurrentThread()");
		}
		// Already sending?
		boolean wasSending = sending.getAndSet(true);

		// Put on queue
		final CompletableFuture<Void> res = new CompletableFuture<Void>();
		sendQueue.add(new Runnable() {
			@Override
			public void run() {
				try {
					jSession.sendMessage((WebSocketMessage<?>) new BinaryMessage(buffer, true)).handle((r, e) -> {
						sendLock.lock();
						try {
							if (e != null) {
								getLog().error("Error sending to cloud. Will close socket: {}", e, e);
								res.completeExceptionally(e);
								close(e, false);
							} else {
								res.complete(null);
								Runnable next = sendQueue.poll();
								if (next == null) {
									sending.set(false);
								} else {
									next.run();
								}
							}
							return (Void) null;
						} finally {
							sendLock.unlock();
						}
					});
				} catch (Exception e) {
					res.completeExceptionally(e);
				}
			}
		});
		if (!wasSending) {
			// If not sending, send
			sendQueue.remove().run();
		}
		return res;
	}
	
	private void signalCloseStatus(Throwable e, Boolean remote) {
		if (e == null) {
			closeStatus.complete(remote);
		} else {
			closeStatus.completeExceptionally(e);
		}
	}

	public CompletableFuture<Boolean> getCloseStatus() {
		return closeStatus;
	}

	private int updatedataCRCT2W(byte[] array, int off, int len) {
		dataCRCT2W.update(array, off, len);
		return (int) dataCRCT2W.getValue();
	}

	protected abstract void assertClosed();

}

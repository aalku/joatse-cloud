package org.aalku.joatse.cloud.service.sharing.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.aalku.joatse.cloud.service.AbstractToSocketConnection;
import org.aalku.joatse.cloud.service.JWSSession;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles file download requests using NEW_SOCKET protocol with additionalPayload.
 * The additionalPayload in NEW_SOCKET contains:
 * - Offset: 8 bytes (long) - starting position in file (0 for beginning)
 * - Length: 8 bytes (long) - number of bytes to read (-1 for entire file, 0 for metadata only)
 * 
 * This class manages the binary protocol for requesting and receiving file data from the target.
 */
public class FileReadConnection extends AbstractToSocketConnection {
	
	private static final Logger log = LoggerFactory.getLogger(FileReadConnection.class);
	
	/**
	 * Message types for the data queue
	 */
	private static abstract class QueueMessage {}
	
	private static class DataMessage extends QueueMessage {
		final ByteBuffer data;
		DataMessage(ByteBuffer data) {
			this.data = data;
		}
	}
	
	private static class EOFMessage extends QueueMessage {
		static final EOFMessage INSTANCE = new EOFMessage();
		private EOFMessage() {}
	}
	
	private static class ErrorMessage extends QueueMessage {
		final Throwable error;
		ErrorMessage(Throwable error) {
			this.error = error;
		}
	}
	
	private final FileTunnel fileTunnel;
	private final long offset;
	private final long length;
	
	// TODO: In the future, a queue should not be used as it might fill the memory and avoid backpressure.
	// Consider using a different mechanism that allows proper flow control.
	private final BlockingQueue<QueueMessage> messageQueue = new LinkedBlockingQueue<>();
	private final AtomicReference<JSONObject> metadata = new AtomicReference<>();
	private final AtomicBoolean metadataReceived = new AtomicBoolean(false);
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private final AtomicBoolean errorOccurred = new AtomicBoolean(false);
	private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
	
	// Async callback support
	private final CompletableFuture<JSONObject> metadataFuture = new CompletableFuture<>();
	private volatile java.util.function.Consumer<ByteBuffer> dataConsumer;
	private volatile Runnable eofCallback;
	private volatile java.util.function.Consumer<Throwable> errorCallback;
	private final AtomicBoolean processingQueue = new AtomicBoolean(false);
	
	// Byte stream protocol state
	private ByteBuffer accumulator = ByteBuffer.allocate(8192);
	private enum ParseState { STATUS, METADATA_LENGTH, METADATA, FILE_CONTENT }
	private ParseState parseState = ParseState.STATUS;
	private byte statusByte;
	private int metadataLength;
	
	/**
	 * Create a new file read connection.
	 * Sends NEW_SOCKET with additionalPayload containing offset and length.
	 * 
	 * @param fileTunnel The file tunnel to read from
	 * @param jSession The WebSocket session
	 * @param offset Starting offset in the file (0 for beginning)
	 * @param length Number of bytes to read (-1 for entire file, 0 for metadata only)
	 */
	public FileReadConnection(FileTunnel fileTunnel, JWSSession jSession, long offset, long length) {
		super(fileTunnel.getTargetId(), jSession, createFileRequestPayload(offset, length));
		this.fileTunnel = fileTunnel;
		this.offset = offset;
		this.length = length;
		log.debug("Created FileReadConnection for file {} (targetId={}, socketId={}, offset={}, length={})", 
				fileTunnel.getTargetPath(), targetId, socketId, offset, length);
	}
	
	/**
	 * Create the additionalPayload for NEW_SOCKET containing file request parameters.
	 */
	private static ByteBuffer createFileRequestPayload(long offset, long length) {
		ByteBuffer payload = ByteBuffer.allocate(16);
		payload.putLong(offset);
		payload.putLong(length);
		payload.flip();
		return payload;
	}
	
	/**
	 * Get metadata asynchronously. Returns a CompletableFuture that completes when metadata is received.
	 */
	public CompletableFuture<JSONObject> getMetadataAsync() {
		return metadataFuture;
	}
		
	/**
	 * Set callback to receive data chunks asynchronously as they arrive.
	 * Messages are processed in order without blocking.
	 */
	public void setDataConsumer(java.util.function.Consumer<ByteBuffer> consumer) {
		this.dataConsumer = consumer;
		processQueueAsync();
	}
	
	/**
	 * Set callback to be notified when EOF is reached.
	 */
	public void setEofCallback(Runnable callback) {
		this.eofCallback = callback;
	}
	
	/**
	 * Set callback to be notified of errors.
	 */
	public void setErrorCallback(java.util.function.Consumer<Throwable> callback) {
		this.errorCallback = callback;
	}
	
	/**
	 * Process queued messages asynchronously without blocking.
	 * Uses CAS to ensure only one processing task runs at a time.
	 */
	private void processQueueAsync() {
		// Only one processor at a time
		if (!processingQueue.compareAndSet(false, true)) {
			return;
		}
		
		// Process in a background thread or task
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				while (!closed.get()) {
					QueueMessage msg = messageQueue.poll();
					if (msg == null) {
						// No more messages, exit
						break;
					}
					
					if (msg instanceof DataMessage) {
						DataMessage dataMsg = (DataMessage) msg;
						if (dataConsumer != null) {
							try {
								dataConsumer.accept(dataMsg.data);
							} catch (Exception e) {
								log.error("Error in data consumer callback", e);
							}
						}
					} else if (msg instanceof EOFMessage) {
						log.debug("EOF received for file {} (async)", fileTunnel.getTargetPath());
						if (eofCallback != null) {
							try {
								eofCallback.run();
							} catch (Exception e) {
								log.error("Error in EOF callback", e);
							}
						}
						close();
						break;
					} else if (msg instanceof ErrorMessage) {
						ErrorMessage errMsg = (ErrorMessage) msg;
						log.error("Error message received for file {} (async)", fileTunnel.getTargetPath(), errMsg.error);
						if (errorCallback != null) {
							try {
								errorCallback.accept(errMsg.error);
							} catch (Exception e) {
								log.error("Error in error callback", e);
							}
						}
						close();
						break;
					}
				}
			} finally {
				processingQueue.set(false);
				// Check if more messages arrived while we were finishing
				if (!messageQueue.isEmpty() && !closed.get()) {
					processQueueAsync();
				}
			}
		});
	}
	
	/**
	 * Check if connection has been closed
	 */
	public boolean isClosed() {
		return closed.get();
	}
	
	/**
	 * Signal end of file stream from remote side.
	 * This enqueues an EOF message to be processed by the reader.
	 */
	private void eof() {
		log.debug("Enqueueing EOF for file {} (socketId={})", 
				fileTunnel.getTargetPath(), socketId);
		messageQueue.offer(EOFMessage.INSTANCE);
	}
	
	/**
	 * Get completion future
	 */
	public CompletableFuture<Void> getCompletionFuture() {
		return completionFuture;
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			log.debug("Closing FileReadConnection for file {} (socketId={})", 
					fileTunnel.getTargetPath(), socketId);
			completionFuture.complete(null);
			// Clear any remaining messages to free memory
			messageQueue.clear();
		}
	}

	@Override
	protected void copyFromClientToTargetForever() {
		// Not used for file reads - file data flows only from target to cloud
		// TODO: Log error and disconnect if something is received as that's a protocol violation.
	}

	@Override
	protected void closeInternal(Throwable e, Boolean b) {
		log.debug("FileReadConnection closeInternal for file {} (socketId={})", 
				fileTunnel.getTargetPath(), socketId);
		if (e != null) {
			// Enqueue error message
			errorOccurred.set(true);
			completionFuture.completeExceptionally(e);
			metadataFuture.completeExceptionally(e);
			messageQueue.offer(new ErrorMessage(e));
			// Trigger async processing if callbacks are set
			if (dataConsumer != null || errorCallback != null) {
				processQueueAsync();
			}
		} else {
			// Normal close from remote side - enqueue EOF
			eof();
			// Trigger async processing if callbacks are set
			if (dataConsumer != null || eofCallback != null) {
				processQueueAsync();
			}
		}
	}

	@Override
	protected void assertClosed() {
		// Close state checking
		if (!closed.get()) {
			log.warn("FileReadConnection not properly closed for file {}", fileTunnel.getTargetPath());
		}
	}

	@Override
	protected CompletableFuture<Integer> writeToClient(ByteBuffer buffer) {
		try {
			int totalBytesReceived = buffer.remaining();
			log.info("writeToClient received {} bytes (parseState={})", totalBytesReceived, parseState);
			
			// Accumulate incoming bytes
			while (buffer.hasRemaining()) {
				// Ensure accumulator has space
				if (!accumulator.hasRemaining()) {
					// Expand accumulator
					ByteBuffer newAccumulator = ByteBuffer.allocate(accumulator.capacity() * 2);
					accumulator.flip();
					newAccumulator.put(accumulator);
					accumulator = newAccumulator;
				}
				accumulator.put(buffer);
			}
			
			// Process accumulated bytes
			accumulator.flip();
			boolean continueProcessing = true;
			
			while (continueProcessing && accumulator.hasRemaining()) {
				switch (parseState) {
					case STATUS:
						// Read 1 byte status
						if (accumulator.remaining() >= 1) {
							statusByte = accumulator.get();
							parseState = ParseState.METADATA_LENGTH;
						if (statusByte != 0x01) {
							log.error("File read failed with status: 0x{}", Integer.toHexString(statusByte & 0xFF));
							errorOccurred.set(true);
							IOException ioException = new IOException("File read failed with status: " + statusByte);
							metadataFuture.completeExceptionally(ioException);
							close();
							CompletableFuture<Integer> future = new CompletableFuture<>();
							future.completeExceptionally(ioException);
							return future;
						}
						} else {
							continueProcessing = false;
						}
						break;
						
					case METADATA_LENGTH:
						// Read 4 bytes for metadata length
						if (accumulator.remaining() >= 4) {
							metadataLength = accumulator.getInt();
							parseState = ParseState.METADATA;
							log.debug("File metadata length: {}", metadataLength);
						} else {
							continueProcessing = false;
						}
						break;
						
					case METADATA:
						// Read metadata JSON
						if (accumulator.remaining() >= metadataLength) {
							// We have all metadata bytes
							byte[] metadataBytes = new byte[metadataLength];
							accumulator.get(metadataBytes);
							
						String jsonStr = new String(metadataBytes, java.nio.charset.StandardCharsets.UTF_8);
						JSONObject meta = new JSONObject(jsonStr);
						metadata.set(meta);
						metadataReceived.set(true);
						metadataFuture.complete(meta);
						parseState = ParseState.FILE_CONTENT;
						log.debug("Received file metadata for {}: {}", fileTunnel.getTargetPath(), meta);
						} else {
							// Need more bytes for metadata
							continueProcessing = false;
						}
						break;
						
					case FILE_CONTENT:
						// Queue remaining bytes as file content
						if (accumulator.hasRemaining()) {
							ByteBuffer fileData = ByteBuffer.allocate(accumulator.remaining());
							fileData.put(accumulator);
						fileData.flip();
						log.info("Queueing {} bytes of file content to messageQueue", fileData.remaining());
						messageQueue.offer(new DataMessage(fileData));
						// Trigger async processing if consumer is set
						if (dataConsumer != null) {
							processQueueAsync();
						}
					}
					continueProcessing = false;
						break;
				}
			}
			
			// Compact accumulator for next iteration
			accumulator.compact();
			
			return CompletableFuture.completedFuture(totalBytesReceived);
			
		} catch (Exception e) {
			log.error("Failed to process file data: {}", e.getMessage(), e);
			errorOccurred.set(true);
			messageQueue.offer(new ErrorMessage(e));
			CompletableFuture<Integer> future = new CompletableFuture<>();
			future.completeExceptionally(e);
			return future;
		}
	}

	@Override
	protected Void errorConnectingToFinalTarget(Throwable e) {
		log.error("Error reading file {} (socketId={}): {}", 
				fileTunnel.getTargetPath(), socketId, e.getMessage(), e);
		errorOccurred.set(true);
		metadataFuture.completeExceptionally(e);
		messageQueue.offer(new ErrorMessage(e));
		// Trigger async processing if callbacks are set
		if (dataConsumer != null || errorCallback != null) {
			processQueueAsync();
		}
		return null;
	}

	@Override
	protected Logger getLog() {
		return log;
	}
}

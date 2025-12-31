package org.aalku.joatse.cloud.service.sharing.folder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.aalku.joatse.cloud.service.AbstractToSocketConnection;
import org.aalku.joatse.cloud.service.JWSSession;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles folder operation requests using NEW_SOCKET protocol.
 * 
 * Protocol format (MESSAGE_TYPE_NEW_SOCKET, type 0x01):
 * - Socket ID: 8 bytes (long) - managed by AbstractToSocketConnection
 * - Target ID: 8 bytes (long) - managed by AbstractToSocketConnection
 * - OpCode: 1 byte - operation to perform
 * - Path Length: 4 bytes (int) - length of path in bytes
 * - Path: variable (UTF-8) - path within shared folder (always uses forward slashes)
 * - Operation-specific payload: variable (depends on OpCode)
 * 
 * Response format (via SOCKET_DATA messages):
 * 
 * For JSON-only operations (LIST, STAT, MKDIR, DELETE, RMDIR, MOVE, WRITE):
 * - Status: 1 byte (0x01 = success, other = error)
 * - JSON Length: 4 bytes (int) - length of JSON data
 * - JSON: variable (UTF-8) - operation result or error details
 * 
 * For READ operation (file download):
 * - Status: 1 byte (0x01 = success, other = error)
 * - On success: File Data (binary) - raw file content streamed until connection closes
 * - On error: Error Message (UTF-8 string) - error description until connection closes
 * 
 * Note: READ does not include metadata. Use STAT first to get file size, lastModified, etc.
 * 
 * This class manages a single folder operation. Each operation gets its own connection instance.
 */
public class FolderOperationConnection extends AbstractToSocketConnection {
	
	private static final Logger log = LoggerFactory.getLogger(FolderOperationConnection.class);
	
	/**
	 * Operation codes matching target-side implementation
	 */
	public enum OpCode {
		LIST(0x01),      // List directory with full metadata
		STAT(0x02),      // Get file/folder metadata
		READ(0x03),      // Read file content (no metadata)
		WRITE(0x04),     // Write/overwrite file
		MKDIR(0x05),     // Create new directory
		DELETE(0x06),    // Delete file
		RMDIR(0x07),     // Delete empty directory
		MOVE(0x08);      // Move/rename file or directory
		
		private final byte code;
		
		OpCode(int code) {
			this.code = (byte) code;
		}
		
		public byte getCode() {
			return code;
		}
	}
	
	/**
	 * Sort field for LIST operation
	 */
	public enum SortBy {
		NONE(0x00),
		NAME(0x01),
		SIZE(0x02),
		MODIFIED(0x03),
		TYPE(0x04);
		
		private final byte code;
		
		SortBy(int code) {
			this.code = (byte) code;
		}
		
		public byte getCode() {
			return code;
		}
	}
	
	/**
	 * Sort order for LIST operation
	 */
	public enum SortOrder {
		ASC(0x00),
		DESC(0x01);
		
		private final byte code;
		
		SortOrder(int code) {
			this.code = (byte) code;
		}
		
		public byte getCode() {
			return code;
		}
	}
	
	/**
	 * Message types for the data queue (used in streaming mode)
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
	
	private final FolderTunnel folderTunnel;
	private final OpCode opCode;
	private final String path;
	private final OutputStream outputStream; // Non-null for READ operations with streaming
	
	private final CompletableFuture<JSONObject> resultFuture = new CompletableFuture<>();
	private final AtomicBoolean closed = new AtomicBoolean(false);
	
	// Streaming mode support (for READ operations)
	private final CompletableFuture<JSONObject> metadataFuture = new CompletableFuture<>();
	private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
	private final BlockingQueue<QueueMessage> messageQueue = new LinkedBlockingQueue<>();
	private final AtomicBoolean processingQueue = new AtomicBoolean(false);
	private final AtomicBoolean errorOccurred = new AtomicBoolean(false);
	
	// Byte stream protocol state for response parsing
	private ByteBuffer accumulator = ByteBuffer.allocate(8192);
	private enum ParseState { STATUS, JSON_LENGTH, JSON, ACTUAL_LENGTH, FILE_CONTENT, READ_ERROR_MESSAGE }
	private ParseState parseState = ParseState.STATUS;
	private byte statusByte;
	private int jsonLength;
	private long actualLength; // For READ operation - number of bytes to expect
	
	/**
	 * Create a new folder operation connection for JSON-only operations.
	 * 
	 * @param folderTunnel The folder tunnel
	 * @param jSession The WebSocket session
	 * @param opCode Operation to perform
	 * @param path Path within shared folder (must use forward slashes)
	 * @param additionalPayload Optional operation-specific payload (can be null)
	 */
	public FolderOperationConnection(FolderTunnel folderTunnel, JWSSession jSession, 
			OpCode opCode, String path, ByteBuffer additionalPayload) {
		this(folderTunnel, jSession, opCode, path, additionalPayload, null);
	}
	
	/**
	 * Create a new folder operation connection with optional streaming support.
	 * 
	 * @param folderTunnel The folder tunnel
	 * @param jSession The WebSocket session
	 * @param opCode Operation to perform
	 * @param path Path within shared folder (must use forward slashes)
	 * @param additionalPayload Optional operation-specific payload (can be null)
	 * @param outputStream Optional output stream for READ operations (enables streaming mode)
	 */
	public FolderOperationConnection(FolderTunnel folderTunnel, JWSSession jSession, 
			OpCode opCode, String path, ByteBuffer additionalPayload, OutputStream outputStream) {
		super(folderTunnel.getTargetId(), jSession, 
				createOperationPayload(opCode, path, additionalPayload));
		this.folderTunnel = folderTunnel;
		this.opCode = opCode;
		this.path = path;
		this.outputStream = outputStream;
		
		log.debug("Created FolderOperationConnection for operation {} on path {} (targetId={}, socketId={}, streaming={})",
				opCode, path, targetId, socketId, outputStream != null);
		
		// If streaming mode, set up data consumer
		if (isStreamingMode()) {
			processQueueAsync(this::writeDataToOutputStream);
		}
	}
	
	/**
	 * Create the additionalPayload for NEW_SOCKET containing folder operation request.
	 * Format: OpCode (1 byte) + Path Length (4 bytes) + Path (UTF-8) + operation-specific payload
	 */
	private static ByteBuffer createOperationPayload(OpCode opCode, String path, ByteBuffer additionalPayload) {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		int additionalSize = (additionalPayload != null) ? additionalPayload.remaining() : 0;
		int totalSize = 1 + 4 + pathBytes.length + additionalSize;
		
		ByteBuffer payload = ByteBuffer.allocate(totalSize);
		payload.put(opCode.getCode());
		payload.putInt(pathBytes.length);
		payload.put(pathBytes);
		
		if (additionalPayload != null) {
			payload.put(additionalPayload);
		}
		
		payload.flip();
		return payload;
	}
	
	/**
	 * Check if this connection is in streaming mode (READ operation with OutputStream).
	 */
	private boolean isStreamingMode() {
		return outputStream != null;
	}
	
	/**
	 * Get the result asynchronously. Returns a CompletableFuture that completes when the operation finishes.
	 * For successful operations, completes with JSON result.
	 * For errors, completes exceptionally with IOException containing error details.
	 * 
	 * Note: For streaming READ operations, use getMetadataAsync() and getCompletionFuture() instead.
	 */
	public CompletableFuture<JSONObject> getResultAsync() {
		return resultFuture;
	}
	
	/**
	 * Get metadata asynchronously (for streaming READ operations).
	 * Returns a CompletableFuture that completes when metadata is received.
	 */
	public CompletableFuture<JSONObject> getMetadataAsync() {
		return metadataFuture;
	}
	
	/**
	 * Get completion future (for streaming READ operations).
	 * Returns a CompletableFuture that completes when the entire file has been streamed.
	 */
	public CompletableFuture<Void> getCompletionFuture() {
		return completionFuture;
	}
	
	/**
	 * Get the actual length of file content (for READ operations).
	 * This is the actual_length value received from the target in the READ response.
	 * Should only be called after getMetadataAsync() completes successfully.
	 * 
	 * @return The actual length in bytes
	 * @throws IllegalStateException if called before metadata is received or for non-READ operations
	 */
	public long getActualLength() {
		if (!isStreamingMode()) {
			throw new IllegalStateException("getActualLength() only available for READ operations");
		}
		if (!metadataFuture.isDone() || metadataFuture.isCompletedExceptionally()) {
			throw new IllegalStateException("Metadata not yet received or failed");
		}
		return actualLength;
	}
	
	/**
	 * Send data to the target for WRITE operations.
	 * This sends a SOCKET_DATA message containing the provided data.
	 * 
	 * @param data The data to send (position to limit will be sent)
	 * @return CompletableFuture that completes when the data has been sent
	 */
	public CompletableFuture<Void> sendDataToTarget(ByteBuffer data) {
		if (closed.get()) {
			CompletableFuture<Void> future = new CompletableFuture<>();
			future.completeExceptionally(new IOException("Connection is closed"));
			return future;
		}
		log.debug("sendDataToTarget: {} bytes for operation {} (socketId={})", data.remaining(), opCode, socketId);
		return sendDataMessageToTarget(data);
	}
	
	/**
	 * Create payload for LIST operation.
	 * Format: offset (8 bytes) + length (8 bytes) + sortBy (1 byte) + sortOrder (1 byte)
	 */
	public static ByteBuffer createListPayload(long offset, long length, SortBy sortBy, SortOrder sortOrder) {
		ByteBuffer payload = ByteBuffer.allocate(18);
		payload.putLong(offset);
		payload.putLong(length);
		payload.put(sortBy.getCode());
		payload.put(sortOrder.getCode());
		payload.flip();
		return payload;
	}
	
	/**
	 * Create payload for READ operation.
	 * Format: offset (8 bytes) + length (8 bytes)
	 */
	public static ByteBuffer createReadPayload(long offset, long length) {
		ByteBuffer payload = ByteBuffer.allocate(16);
		payload.putLong(offset);
		payload.putLong(length);
		payload.flip();
		return payload;
	}
	
	/**
	 * Create payload for WRITE operation.
	 * Format: offset (8 bytes) + length (8 bytes)
	 * Note: Actual file content follows via subsequent SOCKET_DATA messages
	 */
	public static ByteBuffer createWritePayload(long offset, long length) {
		ByteBuffer payload = ByteBuffer.allocate(16);
		payload.putLong(offset);
		payload.putLong(length);
		payload.flip();
		return payload;
	}
	
	/**
	 * Create payload for MOVE operation.
	 * Format: newPathLength (4 bytes) + newPath (UTF-8)
	 */
	public static ByteBuffer createMovePayload(String newPath) {
		byte[] newPathBytes = newPath.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(4 + newPathBytes.length);
		payload.putInt(newPathBytes.length);
		payload.put(newPathBytes);
		payload.flip();
		return payload;
	}
	
	/**
	 * Process incoming message data.
	 */
	protected void onMessage(ByteBuffer message) {
		if (closed.get()) {
			log.debug("Ignoring message on closed connection (socketId={})", socketId);
			return;
		}
		
		try {
			parseResponse(message);
		} catch (Exception e) {
			log.error("Error parsing folder operation response (socketId={})", socketId, e);
			resultFuture.completeExceptionally(new IOException("Protocol error: " + e.getMessage(), e));
			close(e, true);
		}
	}
	
	@Override
	protected CompletableFuture<Integer> writeToClient(ByteBuffer buffer) {
		try {
			int totalBytesReceived = buffer.remaining();
			log.debug("writeToClient received {} bytes for operation {} (parseState={}, socketId={})", 
					totalBytesReceived, opCode, parseState, socketId);
			
			// Pass to onMessage for processing
			onMessage(buffer);
			
			return CompletableFuture.completedFuture(totalBytesReceived);
		} catch (Exception e) {
			log.error("Error in writeToClient (socketId={})", socketId, e);
			CompletableFuture<Integer> future = new CompletableFuture<>();
			future.completeExceptionally(e);
			return future;
		}
	}
	
	/**
	 * Parse response bytes according to protocol.
	 * 
	 * For JSON-only operations (LIST, STAT, MKDIR, DELETE, RMDIR, MOVE, WRITE):
	 *   Status (1 byte) + JSON Length (4 bytes) + JSON (variable)
	 * 
	 * For streaming READ operations:
	 *   Status (1 byte, 0x01 = success) + File Data (stream until connection close)
	 *   Status (1 byte, != 0x01 = error) + Error Message (UTF-8 string until connection close)
	 * 
	 * Multiple SOCKET_DATA messages form a continuous byte stream.
	 */
	private void parseResponse(ByteBuffer message) throws IOException {
		while (message.hasRemaining()) {
			switch (parseState) {
				case STATUS:
					statusByte = message.get();
					log.debug("Parsed status byte: {} (socketId={}, streaming={})", statusByte, socketId, isStreamingMode());
					
					// Determine next state based on operation mode
					if (isStreamingMode()) {
						// READ operation: status + actual_length + content (or status + error message)
						if (statusByte == 0x01) {
							// Success - read actual length before file content
							parseState = ParseState.ACTUAL_LENGTH;
						} else {
							// Error - remaining bytes are UTF-8 error message
							log.error("Operation failed with status: 0x{}", Integer.toHexString(statusByte & 0xFF));
							parseState = ParseState.READ_ERROR_MESSAGE;
							errorOccurred.set(true);
						}
					} else {
						// JSON-only operations
						parseState = ParseState.JSON_LENGTH;
						if (statusByte != 0x01) {
							log.error("Operation failed with status: 0x{}", Integer.toHexString(statusByte & 0xFF));
							errorOccurred.set(true);
						}
					}
					break;
				
				case ACTUAL_LENGTH:
					// READ operation: parse 8-byte actual length
					if (!ensureBytes(message, 8)) {
						return;
					}
					actualLength = accumulator.getLong();
					accumulator.compact();
					log.debug("Parsed actual length: {} bytes (socketId={})", actualLength, socketId);
					
					if (actualLength < 0 || actualLength > Long.MAX_VALUE) {
						throw new IOException("Invalid actual length: " + actualLength);
					}
					
					// Signal that we're ready to stream (metadata future completed with empty object)
					metadataFuture.complete(new JSONObject());
					parseState = ParseState.FILE_CONTENT;
					break;
				
				case READ_ERROR_MESSAGE:
					// Accumulate error message bytes - will be processed on connection close
					if (message.hasRemaining()) {
						ensureAccumulatorCapacity(message.remaining());
						accumulator.put(message);
					}
					return; // Wait for more data or connection close
					
				case JSON_LENGTH:
					if (!ensureBytes(message, 4)) {
						return;
					}
					jsonLength = accumulator.getInt();
					accumulator.compact();
					parseState = ParseState.JSON;
					log.debug("Parsed JSON length: {} bytes (socketId={})", jsonLength, socketId);
					
					if (jsonLength <= 0 || jsonLength > 10 * 1024 * 1024) {
						throw new IOException("Invalid JSON length: " + jsonLength);
					}
					break;
					
				case JSON:
					if (!ensureBytes(message, jsonLength)) {
						return;
					}
					
					byte[] jsonBytes = new byte[jsonLength];
					accumulator.get(jsonBytes);
					accumulator.compact();
					
					String jsonString = new String(jsonBytes, StandardCharsets.UTF_8);
					handleJsonResponse(jsonString);
					return; // Response complete for JSON-only operations
					
				case FILE_CONTENT:
					// Stream remaining bytes from message buffer as file content
					if (message.hasRemaining()) {
						ByteBuffer fileData = ByteBuffer.allocate(message.remaining());
						fileData.put(message);
						fileData.flip();
						log.debug("Queueing {} bytes of file content from message buffer", fileData.remaining());
						messageQueue.offer(new DataMessage(fileData));
						// Trigger queue processing - needed in case processQueueAsync already drained the queue
						processQueueAsync(this::writeDataToOutputStream);
					}
					return; // Wait for more data or connection close
			}
		}
	}
	
	/**
	 * Ensure the accumulator has enough capacity for the given number of bytes.
	 */
	private void ensureAccumulatorCapacity(int additionalBytes) {
		if (accumulator.remaining() < additionalBytes) {
			int newCapacity = accumulator.position() + additionalBytes;
			ByteBuffer newAccumulator = ByteBuffer.allocate(newCapacity);
			accumulator.flip();
			newAccumulator.put(accumulator);
			accumulator = newAccumulator;
		}
	}
	
	/**
	 * Handle JSON response for JSON-only operations
	 */
	private void handleJsonResponse(String jsonString) {
		log.info("Received JSON response for {} operation on path {} (socketId={}, status={}): {}", 
				opCode, path, socketId, "0x%02X".formatted(statusByte), jsonString);
		
		JSONObject json = new JSONObject(jsonString);
		
		if (statusByte == 0x01) {
			// Success
			resultFuture.complete(json);
		} else {
			// Error - JSON should contain error details
			String errorMsg = json.optString("error", "Unknown error");
			log.error("Folder operation failed with status {} for {} on path {}: {}. Full JSON: {}",
					"0x%02X".formatted(statusByte), opCode, path, errorMsg, jsonString);
			resultFuture.completeExceptionally(new IOException("Operation failed: " + errorMsg));
		}
	}
	
	/**
	 * Write data chunk to output stream
	 */
	private void writeDataToOutputStream(ByteBuffer data) {
		try {
			if (data.hasArray()) {
				outputStream.write(data.array(), data.arrayOffset() + data.position(), data.remaining());
			} else {
				byte[] bytes = new byte[data.remaining()];
				data.get(bytes);
				outputStream.write(bytes);
			}
			outputStream.flush();
		} catch (IOException e) {
			log.error("Failed to write data to output stream for file {}", path, e);
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Process queued messages asynchronously without blocking.
	 */
	private void processQueueAsync(java.util.function.Consumer<ByteBuffer> dataConsumer) {
		if (!processingQueue.compareAndSet(false, true)) {
			return;
		}
		
		CompletableFuture.runAsync(() -> {
			try {
				// Process all messages until queue is empty
				// Don't check closed flag here - we need to process EOF message
				while (true) {
					QueueMessage msg = messageQueue.poll();
					if (msg == null) {
						break;
					}
					
					if (msg instanceof DataMessage dataMsg) {
						if (dataConsumer != null) {
							try {
								dataConsumer.accept(dataMsg.data);
							} catch (Exception e) {
								log.error("Error in data consumer callback", e);
								completionFuture.completeExceptionally(e);
								close();
								break;
							}
						}
					} else if (msg instanceof EOFMessage) {
						log.debug("EOF dequeued for file {} - completing", path);
						if (!completionFuture.isDone()) {
							completionFuture.complete(null);
						}
						break;
					} else if (msg instanceof ErrorMessage errMsg) {
						log.error("Error dequeued for file {}", path, errMsg.error);
						if (!completionFuture.isDone()) {
							completionFuture.completeExceptionally(errMsg.error);
						}
						break;
					}
				}
			} finally {
				processingQueue.set(false);
				// Restart if there are more messages and we haven't completed
				if (!messageQueue.isEmpty() && !completionFuture.isDone()) {
					processQueueAsync(dataConsumer);
				}
			}
		});
	}
	
	/**
	 * Signal end of file stream from remote side.
	 */
	private void eof() {
		log.debug("Enqueueing EOF for file {} (socketId={})", path, socketId);
		messageQueue.offer(EOFMessage.INSTANCE);
	}
	
	/**
	 * Ensure we have at least 'needed' bytes accumulated in the buffer.
	 * Returns true if we have enough, false if we need more data.
	 */
	private boolean ensureBytes(ByteBuffer source, int needed) {
		int available = accumulator.position();
		int remaining = needed - available;
		
		if (remaining <= 0) {
			// We already have enough
			accumulator.flip();
			return true;
		}
		
		// Need more bytes
		if (!source.hasRemaining()) {
			return false; // Wait for next message
		}
		
		// Copy what we can from source
		int toCopy = Math.min(remaining, source.remaining());
		
		// Ensure accumulator has space
		if (accumulator.remaining() < toCopy) {
			// Need to expand accumulator
			int newCapacity = accumulator.position() + toCopy + 1024;
			ByteBuffer newAccumulator = ByteBuffer.allocate(newCapacity);
			accumulator.flip();
			newAccumulator.put(accumulator);
			accumulator = newAccumulator;
		}
		
		// Copy bytes
		int originalLimit = source.limit();
		source.limit(source.position() + toCopy);
		accumulator.put(source);
		source.limit(originalLimit);
		
		// Check if we now have enough
		if (accumulator.position() >= needed) {
			accumulator.flip();
			return true;
		}
		
		return false; // Still need more
	}
	
	@Override
	protected void closeInternal(Throwable e, Boolean remote) {
		log.debug("FolderOperationConnection closeInternal for operation {} on path {} (socketId={}, error={}, remote={}, streaming={})",
				opCode, path, socketId, e != null ? e.getMessage() : "none", remote, isStreamingMode());
		
		if (isStreamingMode()) {
			// Streaming mode - handle metadata and completion futures
			
			// Check if we received an error message (READ operation error)
			if (parseState == ParseState.READ_ERROR_MESSAGE) {
				// Extract error message from accumulator
				accumulator.flip();
				String errorMsg = StandardCharsets.UTF_8.decode(accumulator).toString();
				if (errorMsg.isEmpty()) {
					errorMsg = "Unknown error (status: 0x" + Integer.toHexString(statusByte & 0xFF) + ")";
				}
				log.error("READ operation failed for path {}: {}", path, errorMsg);
				IOException exception = new IOException("File read failed: " + errorMsg);
				closed.set(true);
				if (!metadataFuture.isDone()) {
					metadataFuture.completeExceptionally(exception);
				}
				messageQueue.clear();
				completionFuture.completeExceptionally(exception);
				return;
			}
			
			if (e != null) {
				closed.set(true);
				errorOccurred.set(true);
				if (!metadataFuture.isDone()) {
					metadataFuture.completeExceptionally(e);
				}
				// Don't clear queue - let error message be processed
				messageQueue.offer(new ErrorMessage(e));
				processQueueAsync(this::writeDataToOutputStream);
			} else {
				// Normal close - enqueue EOF BEFORE setting closed flag
				// so that processQueueAsync can restart if needed
				eof();
				closed.set(true);
				processQueueAsync(this::writeDataToOutputStream);
			}
		} else {
			// JSON-only mode - handle result future
			closed.set(true);
			messageQueue.clear();
			if (!resultFuture.isDone()) {
				if (e != null) {
					log.error("Connection closed with error before operation completed: {}", e.getMessage(), e);
					resultFuture.completeExceptionally(new IOException("Connection closed with error: " + e.getMessage(), e));
				} else {
					log.error("Connection closed before operation completed");
					resultFuture.completeExceptionally(new IOException("Connection closed before operation completed"));
				}
			}
		}
	}
	
	@Override
	protected void copyFromClientToTargetForever() {
		// For most folder operations (LIST, STAT, etc.), data flows only from target to cloud
		// WRITE operation will need to implement streaming in the future
	}
	
	@Override
	protected void assertClosed() {
		if (!closed.get()) {
			log.warn("FolderOperationConnection not properly closed for operation {} on path {} (targetId={})",
					opCode, path, targetId);
		}
	}
	
	@Override
	protected Void errorConnectingToFinalTarget(Throwable e) {
		log.error("Error connecting to target for operation {} on path {} (socketId={}): {}", 
				opCode, path, socketId, e.getMessage(), e);
		
		if (isStreamingMode()) {
			errorOccurred.set(true);
			if (!metadataFuture.isDone()) {
				metadataFuture.completeExceptionally(e);
			}
			messageQueue.offer(new ErrorMessage(e));
			processQueueAsync(this::writeDataToOutputStream);
		} else {
			if (!resultFuture.isDone()) {
				resultFuture.completeExceptionally(e);
			}
		}
		return null;
	}
	
	@Override
	protected Logger getLog() {
		return log;
	}
	
	/**
	 * Check if connection has been closed
	 */
	public boolean isClosed() {
		return closed.get();
	}
	
	public FolderTunnel getFolderTunnel() {
		return folderTunnel;
	}
	
	public OpCode getOpCode() {
		return opCode;
	}
	
	public String getPath() {
		return path;
	}
}

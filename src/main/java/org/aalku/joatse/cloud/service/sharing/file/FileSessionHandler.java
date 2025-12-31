package org.aalku.joatse.cloud.service.sharing.file;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.aalku.joatse.cloud.service.JWSSession;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A FileSessionHandler instance handles file sharing operations for a single JWSSession.
 * This handler encapsulates the logic for creating file read connections and managing
 * file-related operations with the target.
 */
public class FileSessionHandler {

	/**
	 * Result of an async file read operation containing both metadata and completion futures.
	 * The metadata future completes with file metadata (filename, size, contentType, etc.).
	 * The completion future completes when the entire file transfer is done.
	 * Both futures will fail if errors occur during the operation.
	 */
	public record FileReadResult(
		CompletableFuture<JSONObject> metadata,
		CompletableFuture<Void> completion
	) {}

	private static final Logger log = LoggerFactory.getLogger(FileSessionHandler.class);

	private final JWSSession jWSSession;
	private final SharedResourceLot srl;

	public FileSessionHandler(JWSSession jWSSession, SharedResourceLot srl) {
		this.jWSSession = jWSSession;
		this.srl = srl;
	}

	/**
	 * Creates a new FileReadConnection for reading a file from the target.
	 * 
	 * @param fileTunnel The file tunnel to read from
	 * @param offset The byte offset to start reading from
	 * @param length The number of bytes to read, or -1 for entire file
	 * @return A new FileReadConnection instance
	 */
	public FileReadConnection createFileReadConnection(FileTunnel fileTunnel, long offset, long length) {
		log.debug("Creating file read connection for tunnel {} (offset={}, length={})", 
				fileTunnel.getTargetId(), offset, length);
		return new FileReadConnection(fileTunnel, jWSSession, offset, length);
	}

	/**
	 * Initiates an async file read operation with callbacks.
	 * This method encapsulates the entire async file reading lifecycle including
	 * metadata retrieval, data streaming, EOF handling, and error handling.
	 * 
	 * @param fileTunnel The file tunnel to read from
	 * @param offset The byte offset to start reading from
	 * @param length The number of bytes to read, or -1 for entire file, 0 for metadata only
	 * @param scheduler Scheduler for timeout handling
	 * @param metadataConsumer Consumer for file metadata (called once)
	 * @param dataConsumer Consumer for file data chunks as ByteBuffer (called multiple times)
	 * @param eofCallback Callback when file reading completes successfully
	 * @param errorCallback Callback when an error occurs (receives Throwable)
	 * @return The FileReadConnection instance for cleanup purposes
	 */
	/**
	 * Initiates an async file read operation.
	 * 
	 * @param fileTunnel The file tunnel to read from
	 * @param offset Starting offset in the file
	 * @param length Number of bytes to read (-1 for entire file, 0 for metadata only)
	 * @param scheduler Scheduler for timeout handling
	 * @param outputStream OutputStream to write file data to
	 * @return FileReadResult containing both metadata and completion CompletableFutures
	 */
	public FileReadResult readFileAsync(
			FileTunnel fileTunnel,
			long offset,
			long length,
			ScheduledExecutorService scheduler,
			java.io.OutputStream outputStream) {
		
		log.info("Initiating async file read for tunnel {} (offset={}, length={})", 
				fileTunnel.getTargetId(), offset, length);
		
		// Create file read connection
		FileReadConnection fileConn = createFileReadConnection(fileTunnel, offset, length);
		
		// Get the completion future
		CompletableFuture<Void> rawCompletionFuture = fileConn.getCompletionFuture();
		CompletableFuture<JSONObject> rawMetadataFuture = fileConn.getMetadataAsync();
		
		// Create wrapper futures with timeout and error handling logic
		CompletableFuture<JSONObject> wrappedMetadataFuture = new CompletableFuture<>();
		CompletableFuture<Void> wrappedCompletionFuture = new CompletableFuture<>();
		
		// Add timeout for metadata
		scheduler.schedule(() -> {
			if (!wrappedMetadataFuture.isDone()) {
				Exception timeoutError = new java.util.concurrent.TimeoutException("Metadata timeout");
				wrappedMetadataFuture.completeExceptionally(timeoutError);
				// Propagate to completion future
				if (!wrappedCompletionFuture.isDone()) {
					wrappedCompletionFuture.completeExceptionally(timeoutError);
				}
			}
		}, 30, TimeUnit.SECONDS);
		
		// Handle metadata with error checking and propagation
		rawMetadataFuture.whenComplete((metadata, metadataError) -> {
			if (metadataError != null) {
				log.error("Error getting metadata for {}: {}", fileTunnel.getTargetPath(), metadataError.getMessage());
				Exception wrappedError = new Exception("Timeout or error waiting for file metadata: " + metadataError.getMessage(), metadataError);
				if (!wrappedMetadataFuture.isDone()) {
					wrappedMetadataFuture.completeExceptionally(wrappedError);
				}
				// Propagate to completion future
				if (!wrappedCompletionFuture.isDone()) {
					wrappedCompletionFuture.completeExceptionally(wrappedError);
				}
				return;
			}
			
			try {
				// Check for error in metadata
				if (metadata.has("error")) {
					String error = metadata.getString("error");
					log.warn("File request failed: {}", error);
					Exception metadataError2 = new Exception(error);
					if (!wrappedMetadataFuture.isDone()) {
						wrappedMetadataFuture.completeExceptionally(metadataError2);
					}
					// Propagate to completion future
					if (!wrappedCompletionFuture.isDone()) {
						wrappedCompletionFuture.completeExceptionally(metadataError2);
					}
					return;
				}
				
				// Metadata is valid - complete the metadata future
				if (!wrappedMetadataFuture.isDone()) {
					wrappedMetadataFuture.complete(metadata);
				}
				
				// If metadata-only request (length == 0), complete both futures
				if (length == 0) {
					if (!wrappedCompletionFuture.isDone()) {
						wrappedCompletionFuture.complete(null);
					}
					return;
				}
				
				// Set up data streaming - convert ByteBuffer to bytes and write to OutputStream
				fileConn.setDataConsumer(dataBuffer -> {
					try {
						byte[] bytes = new byte[dataBuffer.remaining()];
						dataBuffer.get(bytes);
						outputStream.write(bytes);
						outputStream.flush();
					} catch (java.io.IOException e) {
						log.error("Error writing file data to output stream: {}", e.getMessage());
						fileConn.close(e, true);
					}
				});
				
			} catch (Exception e) {
				log.error("Error processing metadata for {}: {}", fileTunnel.getTargetPath(), e.getMessage(), e);
				if (!wrappedMetadataFuture.isDone()) {
					wrappedMetadataFuture.completeExceptionally(e);
				}
				if (!wrappedCompletionFuture.isDone()) {
					wrappedCompletionFuture.completeExceptionally(e);
				}
			}
		});
		
	// Link raw completion future to wrapped completion future
	// (only if not already completed by metadata errors or length=0 case)
	rawCompletionFuture.whenComplete((result, error) -> {
		if (!wrappedCompletionFuture.isDone()) {
			if (error != null) {
				wrappedCompletionFuture.completeExceptionally(error);
			} else {
				wrappedCompletionFuture.complete(null);
			}
		}
	});
	
	return new FileReadResult(wrappedMetadataFuture, wrappedCompletionFuture);
}

	/**
	 * Extracts the filename from a file path.
	 * 
	 * @param path The file path
	 * @return The filename, or "file" if path is empty
	 */
	public String extractFilename(String path) {
		if (path == null || path.isEmpty()) {
			return "file";
		}
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash >= 0 && lastSlash < path.length() - 1) {
			return path.substring(lastSlash + 1);
		}
		return path;
	}

	/**
	 * Gets the underlying JWSSession.
	 * 
	 * @return The JWSSession associated with this handler
	 */
	public JWSSession getSession() {
		return jWSSession;
	}
}

package org.aalku.joatse.cloud.service.sharing.folder;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import org.aalku.joatse.cloud.service.JWSSession;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides low-level folder operations for a specific FolderTunnel.
 * 
 * This class abstracts the protocol details and provides clean methods for each
 * folder operation (list, stat, read, write, delete, move, mkdir, rmdir).
 * 
 * Each operation creates an internal FolderOperationConnection, uses it, and closes it.
 * FolderSessionHandler should use this class instead of FolderOperationConnection directly.
 */
public class FolderOperations {

	private static final Logger log = LoggerFactory.getLogger(FolderOperations.class);

	/**
	 * Sort field for list operations.
	 * Re-exported from FolderOperationConnection for convenience.
	 */
	public static enum SortBy {
		NONE, NAME, SIZE, DATE
	}

	/**
	 * Sort direction for list operations.
	 * Re-exported from FolderOperationConnection for convenience.
	 */
	public static enum SortOrder {
		ASC, DESC
	}

	private final FolderTunnel folderTunnel;
	private final JWSSession jWSSession;

	/**
	 * Create a FolderOperations instance for the given tunnel and session.
	 * 
	 * @param folderTunnel The folder tunnel to operate on
	 * @param jWSSession The WebSocket session for communication
	 */
	public FolderOperations(FolderTunnel folderTunnel, JWSSession jWSSession) {
		this.folderTunnel = folderTunnel;
		this.jWSSession = jWSSession;
	}

	/**
	 * List directory contents with pagination and sorting.
	 * 
	 * @param path Path within the shared folder
	 * @param offset Starting item index (0-based)
	 * @param length Maximum number of items to return (-1 for all)
	 * @param sortBy Sort field
	 * @param sortOrder Sort direction
	 * @return CompletableFuture with JSON result containing items array
	 */
	public CompletableFuture<JSONObject> list(String path, long offset, int length, 
			SortBy sortBy, SortOrder sortOrder) {
		
		log.debug("list: path={}, offset={}, length={}, sortBy={}, sortOrder={}", 
				path, offset, length, sortBy, sortOrder);
		
		// Map to FolderOperationConnection enums
		FolderOperationConnection.SortBy connSortBy = FolderOperationConnection.SortBy.valueOf(sortBy.name());
		FolderOperationConnection.SortOrder connSortOrder = FolderOperationConnection.SortOrder.valueOf(sortOrder.name());
		
		ByteBuffer payload = FolderOperationConnection.createListPayload(offset, length, connSortBy, connSortOrder);
		
		FolderOperationConnection connection = new FolderOperationConnection(
			folderTunnel,
			jWSSession,
			FolderOperationConnection.OpCode.LIST,
			path,
			payload
		);
		
		return connection.getResultAsync();
	}

	/**
	 * Get metadata for a file or directory.
	 * 
	 * @param path Path within the shared folder
	 * @return CompletableFuture with JSON result containing metadata
	 */
	public CompletableFuture<JSONObject> stat(String path) {
		log.debug("stat: path={}", path);
		
		FolderOperationConnection connection = new FolderOperationConnection(
			folderTunnel,
			jWSSession,
			FolderOperationConnection.OpCode.STAT,
			path,
			null
		);
		
		return connection.getResultAsync();
	}

	/**
	 * Read file content and stream to the provided OutputStream.
	 * 
	 * @param path Path within the shared folder
	 * @param offset Starting byte position (0 for beginning)
	 * @param length Bytes to read (-1 for entire file from offset)
	 * @param outputStream Stream to write file content to
	 * @return ReadOperation that provides metadata future, completion future, and actual length
	 */
	public ReadOperation read(String path, long offset, long length, OutputStream outputStream) {
		log.debug("read: path={}, offset={}, length={}", path, offset, length);
		
		ByteBuffer payload = FolderOperationConnection.createReadPayload(offset, length);
		
		try {
			FolderOperationConnection connection = new FolderOperationConnection(
				folderTunnel,
				jWSSession,
				FolderOperationConnection.OpCode.READ,
				path,
				payload,
				outputStream
			);
			
			return new ReadOperation(connection);
		} catch (Exception e) {
			// Return a failed ReadOperation
			return new ReadOperation(e);
		}
	}

	/**
	 * Represents an in-progress READ operation with streaming support.
	 */
	public static class ReadOperation {
		private final FolderOperationConnection connection;
		private final CompletableFuture<JSONObject> metadataFuture;
		private final CompletableFuture<Void> completionFuture;
		private final Exception initError;
		
		ReadOperation(FolderOperationConnection connection) {
			this.connection = connection;
			this.metadataFuture = connection.getMetadataAsync();
			this.completionFuture = connection.getCompletionFuture();
			this.initError = null;
		}
		
		ReadOperation(Exception error) {
			this.connection = null;
			this.metadataFuture = new CompletableFuture<>();
			this.metadataFuture.completeExceptionally(error);
			this.completionFuture = new CompletableFuture<>();
			this.completionFuture.completeExceptionally(error);
			this.initError = error;
		}
		
		/**
		 * Get the metadata future. Completes when status + actual_length are received.
		 */
		public CompletableFuture<JSONObject> getMetadataFuture() {
			return metadataFuture;
		}
		
		/**
		 * Get the completion future. Completes when entire file has been streamed.
		 */
		public CompletableFuture<Void> getCompletionFuture() {
			return completionFuture;
		}
		
		/**
		 * Get the actual length of file content.
		 * Should only be called after metadata future completes successfully.
		 */
		public long getActualLength() {
			if (initError != null) {
				throw new IllegalStateException("Operation failed during initialization", initError);
			}
			return connection.getActualLength();
		}
	}

	/**
	 * Delete a file.
	 * 
	 * @param path Path within the shared folder
	 * @return CompletableFuture with JSON result
	 */
	public CompletableFuture<JSONObject> delete(String path) {
		log.debug("delete: path={}", path);
		
		FolderOperationConnection connection = new FolderOperationConnection(
			folderTunnel,
			jWSSession,
			FolderOperationConnection.OpCode.DELETE,
			path,
			null
		);
		
		return connection.getResultAsync();
	}

	/**
	 * Move/rename a file or directory.
	 * 
	 * @param path Current path within the shared folder
	 * @param newPath New path within the shared folder
	 * @return CompletableFuture with JSON result
	 */
	public CompletableFuture<JSONObject> move(String path, String newPath) {
		log.debug("move: path={}, newPath={}", path, newPath);
		
		ByteBuffer payload = FolderOperationConnection.createMovePayload(newPath);
		
		FolderOperationConnection connection = new FolderOperationConnection(
			folderTunnel,
			jWSSession,
			FolderOperationConnection.OpCode.MOVE,
			path,
			payload
		);
		
		return connection.getResultAsync();
	}

	/**
	 * Create a directory (and parent directories if needed).
	 * 
	 * @param path Path within the shared folder
	 * @return CompletableFuture with JSON result
	 */
	public CompletableFuture<JSONObject> mkdir(String path) {
		log.debug("mkdir: path={}", path);
		
		FolderOperationConnection connection = new FolderOperationConnection(
			folderTunnel,
			jWSSession,
			FolderOperationConnection.OpCode.MKDIR,
			path,
			null
		);
		
		return connection.getResultAsync();
	}

	/**
	 * Delete an empty directory.
	 * 
	 * @param path Path within the shared folder
	 * @return CompletableFuture with JSON result
	 */
	public CompletableFuture<JSONObject> rmdir(String path) {
		log.debug("rmdir: path={}", path);
		
		FolderOperationConnection connection = new FolderOperationConnection(
			folderTunnel,
			jWSSession,
			FolderOperationConnection.OpCode.RMDIR,
			path,
			null
		);
		
		return connection.getResultAsync();
	}

	/**
	 * Write file content.
	 * 
	 * @param path Path within the shared folder
	 * @param offset Starting byte position (0 for beginning, -1 for append)
	 * @param length Number of bytes to write
	 * @return WriteOperation that allows sending data and getting the result
	 */
	public WriteOperation write(String path, long offset, long length) {
		log.debug("write: path={}, offset={}, length={}", path, offset, length);
		
		ByteBuffer payload = FolderOperationConnection.createWritePayload(offset, length);
		
		FolderOperationConnection connection = new FolderOperationConnection(
			folderTunnel,
			jWSSession,
			FolderOperationConnection.OpCode.WRITE,
			path,
			payload
		);
		
		return new WriteOperation(connection);
	}

	/**
	 * Represents an in-progress WRITE operation.
	 */
	public static class WriteOperation {
		private final FolderOperationConnection connection;
		
		WriteOperation(FolderOperationConnection connection) {
			this.connection = connection;
		}
		
		/**
		 * Send data to be written to the file.
		 * 
		 * @param data The data to send (position to limit will be sent)
		 * @return CompletableFuture that completes when the data has been sent
		 */
		public CompletableFuture<Void> sendData(ByteBuffer data) {
			return connection.sendDataToTarget(data);
		}
		
		/**
		 * Get the result future. Completes when target responds with success/error.
		 */
		public CompletableFuture<JSONObject> getResultFuture() {
			return connection.getResultAsync();
		}
		
		/**
		 * Close the connection (for error handling).
		 */
		public void close(Throwable error) {
			connection.close(error, false);
		}
	}

	/**
	 * Check if the folder tunnel is read-only.
	 */
	public boolean isReadOnly() {
		return folderTunnel.isReadOnly();
	}

	/**
	 * Get the folder tunnel.
	 */
	public FolderTunnel getFolderTunnel() {
		return folderTunnel;
	}
}

package org.aalku.joatse.cloud.service.sharing.folder;

import java.io.IOException;
import java.io.OutputStream;

import org.aalku.joatse.cloud.service.JWSSession;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.util.MimeTypeUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A FolderSessionHandler instance handles folder sharing operations for a single JWSSession.
 * This handler encapsulates the logic for creating folder operation connections and managing
 * folder-related operations with the target.
 */
public class FolderSessionHandler {

	private static final Logger log = LoggerFactory.getLogger(FolderSessionHandler.class);

	private final JWSSession jWSSession;
	private final SharedResourceLot srl;

	public FolderSessionHandler(JWSSession jWSSession, SharedResourceLot srl) {
		this.jWSSession = jWSSession;
		this.srl = srl;
	}

	/**
	 * Routes and handles a folder operation request.
	 * 
	 * @param servletRequest The HTTP request
	 * @param servletResponse The HTTP response
	 * @param folderTunnel The folder tunnel
	 * @param operation The operation to perform (list, stat, download, upload, mkdir, delete, rmdir, move)
	 * @param path The path within the shared folder
	 * @throws IOException If an I/O error occurs
	 */
	public void handleFolderOperation(
			HttpServletRequest servletRequest,
			HttpServletResponse servletResponse,
			FolderTunnel folderTunnel,
			String operation,
			String path) throws IOException {
		
		log.debug("Handling folder operation: {} for tunnel {} (path={})", 
				operation, folderTunnel.getTargetId(), path);
		
		// Route to appropriate operation handler
		switch (operation) {
			case "list":
				handleFolderList(servletRequest, servletResponse, folderTunnel, path);
				break;
			case "stat":
				handleFolderStat(servletRequest, servletResponse, folderTunnel, path);
				break;
			case "download":
				handleFolderDownload(servletRequest, servletResponse, folderTunnel, path);
				break;
			case "upload":
				handleFolderUpload(servletRequest, servletResponse, folderTunnel, path);
				break;
			case "mkdir":
				handleFolderMkdir(servletRequest, servletResponse, folderTunnel, path);
				break;
			case "delete":
				handleFolderDelete(servletRequest, servletResponse, folderTunnel, path);
				break;
			case "rmdir":
				handleFolderRmdir(servletRequest, servletResponse, folderTunnel, path);
				break;
			case "move":
				handleFolderMove(servletRequest, servletResponse, folderTunnel, path);
				break;
			case "metadata":
				handleFolderMetadata(servletRequest, servletResponse, folderTunnel);
				break;
			default:
				servletResponse.sendError(400, "Unknown operation: " + operation);
		}
	}

	/**
	* Handles the /api/metadata endpoint for folder shares.
	* Returns JSON with description, path, and readOnly.
	*/
	private void handleFolderMetadata(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FolderTunnel folderTunnel) throws IOException {
		JSONObject meta = new JSONObject();
		meta.put("description", folderTunnel.getTargetDescription());
		meta.put("path", folderTunnel.getTargetPath());
		meta.put("readOnly", folderTunnel.isReadOnly());
		servletResponse.setContentType("application/json");
		servletResponse.setCharacterEncoding("UTF-8");
		servletResponse.setStatus(200);
		servletResponse.getWriter().write(meta.toString());
	}

	private void handleFolderList(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			FolderTunnel folderTunnel, String path) throws IOException {
		
		log.debug("LIST operation for tunnel {} path: {}", folderTunnel.getTargetId(), path);
		
		// Parse query parameters for pagination and sorting
		int offset = 0;
		int length = -1; // -1 means all entries
		FolderOperations.SortBy sortBy = FolderOperations.SortBy.NONE;
		FolderOperations.SortOrder sortOrder = FolderOperations.SortOrder.ASC;
		
		try {
			String offsetParam = servletRequest.getParameter("offset");
			if (offsetParam != null) {
				offset = Integer.parseInt(offsetParam);
			}
			
			String lengthParam = servletRequest.getParameter("length");
			if (lengthParam != null) {
				length = Integer.parseInt(lengthParam);
			}
			
			String sortByParam = servletRequest.getParameter("sortBy");
			if (sortByParam != null) {
				sortBy = FolderOperations.SortBy.valueOf(sortByParam.toUpperCase());
			}
			
			String sortOrderParam = servletRequest.getParameter("sortOrder");
			if (sortOrderParam != null) {
				sortOrder = FolderOperations.SortOrder.valueOf(sortOrderParam.toUpperCase());
			}
		} catch (IllegalArgumentException e) {
			log.warn("Invalid query parameter: {}", e.getMessage());
			servletResponse.sendError(400, "Invalid query parameter: " + e.getMessage());
			return;
		}
		
		// Start async processing
		final jakarta.servlet.AsyncContext asyncContext = servletRequest.startAsync();
		asyncContext.setTimeout(0); // No timeout - rely on inactivity timeout instead
		
		// Use FolderOperations abstraction
		FolderOperations folderOps = new FolderOperations(folderTunnel, jWSSession);
		
		// Handle result future
		folderOps.list(path, offset, length, sortBy, sortOrder).whenComplete((jsonResult, error) -> {
			if (error != null) {
				String errorMsg = error.getMessage();
				int statusCode = 500;
				
				// Map common errors to appropriate HTTP status codes
				if (errorMsg != null) {
					if (errorMsg.contains("not found") || errorMsg.contains("does not exist")) {
						statusCode = 404;
					} else if (errorMsg.contains("permission") || errorMsg.contains("access denied")) {
						statusCode = 403;
					} else if (errorMsg.contains("not a directory")) {
						statusCode = 400;
					}
				}
				
				log.error("Error listing folder {}: {}", path, errorMsg, error);
				handleAsyncError(asyncContext, servletResponse, 
						errorMsg != null ? errorMsg : "Error listing folder", statusCode);
			} else {
				try {
					// Enrich each entry with a mime type for clients
					JSONArray items = jsonResult.optJSONArray("items");
					if (items != null) {
						for (int i = 0; i < items.length(); i++) {
							JSONObject item = items.optJSONObject(i);
							if (item == null) {
								continue;
							}
							String itemType = item.optString("type", "");
							if ("directory".equalsIgnoreCase(itemType)) {
								item.put("mimeType", "inode/directory");
							} else {
								String name = item.optString("name", null);
								item.put("mimeType", MimeTypeUtil.guessContentType(name));
							}
						}
					}

					// Send JSON response
					servletResponse.setContentType("application/json");
					servletResponse.setCharacterEncoding("UTF-8");
					servletResponse.setStatus(200);
					servletResponse.getWriter().write(jsonResult.toString());
					completeAsyncContext(asyncContext);
				} catch (IOException e) {
					log.error("Error writing response: {}", e.getMessage(), e);
					handleAsyncError(asyncContext, servletResponse, 
							"Error writing response", 500);
				}
			}
		});
	}

	private void handleFolderStat(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			FolderTunnel folderTunnel, String path) throws IOException {
		
		log.debug("STAT operation for tunnel {} path: {}", folderTunnel.getTargetId(), path);
		
		// Start async processing
		final jakarta.servlet.AsyncContext asyncContext = servletRequest.startAsync();
		asyncContext.setTimeout(0); // No timeout - rely on inactivity timeout instead
		
		// Use FolderOperations abstraction
		FolderOperations folderOps = new FolderOperations(folderTunnel, jWSSession);
		
		// Handle result future
		folderOps.stat(path).whenComplete((jsonResult, error) -> {
			if (error != null) {
				String errorMsg = error.getMessage();
				int statusCode = 500;
				
				// Map common errors to appropriate HTTP status codes
				if (errorMsg != null) {
					if (errorMsg.contains("not found") || errorMsg.contains("does not exist")) {
						statusCode = 404;
					} else if (errorMsg.contains("permission") || errorMsg.contains("access denied")) {
						statusCode = 403;
					}
				}
				
				log.error("Error getting metadata for {}: {}", path, errorMsg, error);
				handleAsyncError(asyncContext, servletResponse, 
						errorMsg != null ? errorMsg : "Error getting metadata", statusCode);
			} else {
				try {
					// Send JSON response
					servletResponse.setContentType("application/json");
					servletResponse.setCharacterEncoding("UTF-8");
					servletResponse.setStatus(200);
					servletResponse.getWriter().write(jsonResult.toString());
					completeAsyncContext(asyncContext);
				} catch (IOException e) {
					log.error("Error writing response: {}", e.getMessage(), e);
					handleAsyncError(asyncContext, servletResponse, 
							"Error writing response", 500);
				}
			}
		});
	}

	private void handleFolderDownload(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			FolderTunnel folderTunnel, String path) throws IOException {
		
		log.debug("DOWNLOAD operation for tunnel {} path: {}", folderTunnel.getTargetId(), path);
		
		String method = servletRequest.getMethod();
		
		// Only support GET and HEAD
		if (!method.equals("GET") && !method.equals("HEAD")) {
			servletResponse.sendError(405, "Method Not Allowed");
			servletResponse.setHeader("Allow", "GET, HEAD");
			return;
		}
		
		// Parse Range header
		String rangeHeader = servletRequest.getHeader("Range");
		long offset = 0;
		long length = -1; // -1 means entire file
		boolean isRangeRequest = false;
		
		if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
			// Simple range parsing (doesn't support multiple ranges yet)
			String range = rangeHeader.substring(6);
			String[] parts = range.split("-");
			try {
				offset = Long.parseLong(parts[0]);
				if (parts.length > 1 && !parts[1].isEmpty()) {
					long end = Long.parseLong(parts[1]);
					length = end - offset + 1;
				}
				isRangeRequest = true;
			} catch (NumberFormatException e) {
				servletResponse.sendError(416, "Requested Range Not Satisfiable");
				return;
			}
		}
		
		// Start async processing
		final jakarta.servlet.AsyncContext asyncContext = servletRequest.startAsync();
		asyncContext.setTimeout(0); // No timeout - rely on inactivity timeout
		
		final boolean finalIsRangeRequest = isRangeRequest;
		final long finalLength = length;
		final long finalOffset = offset;
		final String finalMethod = method;
		
		// Use FolderOperations abstraction
		FolderOperations folderOps = new FolderOperations(folderTunnel, jWSSession);
		
		// Step 1: Call STAT to get file metadata first
		folderOps.stat(path).whenComplete((statResult, statError) -> {
			if (statError != null) {
				String errorMsg = statError.getMessage();
				int statusCode = 500;
				
				// Map common errors to appropriate HTTP status codes
				if (errorMsg != null) {
					if (errorMsg.contains("not found") || errorMsg.contains("does not exist") || errorMsg.contains("NOT_FOUND")) {
						statusCode = 404;
					} else if (errorMsg.contains("permission") || errorMsg.contains("access denied")) {
						statusCode = 403;
					} else if (errorMsg.contains("is a directory") || errorMsg.contains("IS_DIRECTORY")) {
						statusCode = 400;
					}
				}
				
				log.error("Error getting metadata for {}: {}", path, errorMsg, statError);
				handleAsyncError(asyncContext, servletResponse, 
						errorMsg != null ? errorMsg : "Error reading file", statusCode);
				return;
			}
			
			try {
				// Extract file information from STAT result (but don't send response headers yet)
				String fileName = statResult.optString("name", extractFilename(path));
				long statFileSize = statResult.optLong("size", -1);
				String contentType = MimeTypeUtil.guessContentType(fileName);
				String fileType = statResult.optString("type", "file");
				Long lastModified = statResult.has("lastModified") ? statResult.getLong("lastModified") : null;
				
				// Reject directories
				if ("directory".equals(fileType)) {
					handleAsyncError(asyncContext, servletResponse, "Cannot download a directory", 400);
					return;
				}
				
				// Handle HEAD request (return headers only, no need for READ)
				if (finalMethod.equals("HEAD")) {
					servletResponse.setHeader("Accept-Ranges", "bytes");
					servletResponse.setContentType(contentType);
					if (lastModified != null) {
						servletResponse.setDateHeader("Last-Modified", lastModified);
					}
					if (statFileSize >= 0) {
						servletResponse.setContentLengthLong(statFileSize);
					}
					servletResponse.setStatus(200);
					completeAsyncContext(asyncContext);
					return;
				}
				
				log.debug("STAT completed (size={}), starting READ for {} (range={})", 
						statFileSize, path, finalIsRangeRequest);
				
				// Step 2: Now call READ to stream the actual file content
				// IMPORTANT: Don't send response headers yet - wait for READ to get actual length
				FolderOperations.ReadOperation readOp;
				try {
					readOp = folderOps.read(path, finalOffset, finalLength, new OutputStreamWrapper(servletResponse));
				} catch (IOException e) {
					log.error("Error creating READ operation: {}", e.getMessage(), e);
					handleAsyncError(asyncContext, servletResponse, "Internal error", 500);
					return;
				}
				
				// Wait for READ to parse status + actual_length before sending response headers
				readOp.getMetadataFuture().whenComplete((readMeta, readMetaError) -> {
					if (readMetaError != null) {
						log.error("READ operation failed for {}: {}", path, readMetaError.getMessage(), readMetaError);
						
						// Map READ errors to HTTP status codes
						String errorMsg = readMetaError.getMessage();
						int statusCode = 500;
						if (errorMsg != null) {
							if (errorMsg.contains("not found") || errorMsg.contains("does not exist")) {
								statusCode = 404;
							} else if (errorMsg.contains("permission") || errorMsg.contains("access denied")) {
								statusCode = 403;
							}
						}
						
						handleAsyncError(asyncContext, servletResponse, 
								errorMsg != null ? errorMsg : "Error reading file", statusCode);
						return;
					}
					
					try {
						// Now we have actual_length from READ - use it instead of STAT size
						// This handles files that changed between STAT and READ
						long actualLength = readOp.getActualLength();
						
						log.debug("READ started successfully for {} (actual_length={}, stat_size={})", 
								path, actualLength, statFileSize);
						
						// Now it's safe to send response headers with actual length
						servletResponse.setHeader("Accept-Ranges", "bytes");
						servletResponse.setContentType(contentType);
						if (lastModified != null) {
							servletResponse.setDateHeader("Last-Modified", lastModified);
						}
						
						// Handle range request
						if (finalIsRangeRequest) {
							long rangeEnd = (finalLength == -1) ? actualLength - 1 : finalOffset + finalLength - 1;
							if (rangeEnd >= actualLength) {
								rangeEnd = actualLength - 1;
							}
							long contentLength = rangeEnd - finalOffset + 1;
							
							servletResponse.setStatus(206); // Partial Content
							servletResponse.setHeader("Content-Range",
									"bytes %d-%d/%d".formatted(finalOffset, rangeEnd, actualLength));
							servletResponse.setContentLengthLong(contentLength);
						} else {
							// Full file
							servletResponse.setContentLengthLong(actualLength);
							servletResponse.setStatus(200);
						}
						
						// Flush response headers immediately so client can start receiving
						// without waiting for data buffers to fill
						servletResponse.flushBuffer();
						
					} catch (Exception e) {
						log.error("Error setting response headers: {}", e.getMessage(), e);
						handleAsyncError(asyncContext, servletResponse, "Internal error", 500);
					}
				});
				
				// Handle READ completion (file streaming finished)
				readOp.getCompletionFuture().whenComplete((v, completionError) -> {
					if (completionError != null) {
						log.error("Error streaming file {}: {}", path, completionError.getMessage(), completionError);
						// Response may already be committed, just log the error
					} else {
						log.debug("File download completed successfully for {}", path);
					}
					completeAsyncContext(asyncContext);
				});
				
			} catch (Exception e) {
				log.error("Error preparing download: {}", e.getMessage(), e);
				handleAsyncError(asyncContext, servletResponse, "Internal error", 500);
			}
		});
	}

	private void handleFolderUpload(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			FolderTunnel folderTunnel, String path) throws IOException {
		// Check read-only mode
		if (folderTunnel.isReadOnly()) {
			log.warn("Upload rejected: folder tunnel {} is read-only", folderTunnel.getTargetId());
			servletResponse.sendError(403, "Folder is read-only");
			return;
		}
		
		log.debug("UPLOAD operation for tunnel {} path: {}", folderTunnel.getTargetId(), path);
		
		// Get content length (required for WRITE protocol)
		long contentLength = servletRequest.getContentLengthLong();
		if (contentLength < 0) {
			log.warn("Upload rejected: Content-Length header required");
			servletResponse.sendError(411, "Content-Length header required");
			return;
		}
		
		// Parse optional parameters
		boolean append = "true".equals(servletRequest.getParameter("append"));
		long offset = append ? -1 : 0;
		
		// For new files (not append), use temp file + move for atomicity
		final boolean useTempFile = !append;
		final String tempPath;
		final String finalPath = path;
		
		if (useTempFile) {
			// Generate temp file path in same directory as target
			String uuid = java.util.UUID.randomUUID().toString();
			int lastSlash = path.lastIndexOf('/');
			if (lastSlash >= 0) {
				tempPath = path.substring(0, lastSlash + 1) + ".upload-" + uuid + ".tmp";
			} else {
				tempPath = ".upload-" + uuid + ".tmp";
			}
			log.debug("Using temp file for upload: {} -> {}", tempPath, finalPath);
		} else {
			tempPath = path;
		}
		
		// Start async processing
		final jakarta.servlet.AsyncContext asyncContext = servletRequest.startAsync();
		asyncContext.setTimeout(300000); // 5 minutes for large uploads
		
		// Use FolderOperations abstraction
		FolderOperations folderOps = new FolderOperations(folderTunnel, jWSSession);
		
		// Create WRITE operation (to temp file if useTempFile, otherwise direct)
		FolderOperations.WriteOperation writeOp = folderOps.write(tempPath, offset, contentLength);
		
		// Stream request body to target in a separate thread
		asyncContext.start(() -> {
			try {
				java.io.InputStream inputStream = servletRequest.getInputStream();
				byte[] buffer = new byte[64 * 1024]; // 64KB chunks
				int bytesRead;
				long totalSent = 0;
				
				while ((bytesRead = inputStream.read(buffer)) != -1) {
					java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.wrap(buffer, 0, bytesRead);
					writeOp.sendData(byteBuffer).join(); // Wait for each chunk to be sent
					totalSent += bytesRead;
					log.trace("Upload progress for {}: {} / {} bytes", tempPath, totalSent, contentLength);
				}
				
				log.debug("Upload complete for {}: {} bytes sent, waiting for target response", tempPath, totalSent);
				
			} catch (Exception e) {
				log.error("Error streaming upload for {}: {}", tempPath, e.getMessage(), e);
				writeOp.close(e);
				// Try to clean up temp file on failure
				if (useTempFile) {
					folderOps.delete(tempPath).exceptionally(deleteError -> {
						log.warn("Failed to clean up temp file {}: {}", tempPath, deleteError.getMessage());
						return null;
					});
				}
				handleAsyncError(asyncContext, servletResponse, "Upload failed: " + e.getMessage(), 500);
				return;
			}
			
			// Wait for WRITE target response
			writeOp.getResultFuture().whenComplete((writeResult, writeError) -> {
				if (writeError != null) {
					String errorMsg = writeError.getMessage();
					int statusCode = 500;
					
					if (errorMsg != null) {
						if (errorMsg.contains("NOT_FOUND") || errorMsg.contains("not found")) {
							statusCode = 404;
						} else if (errorMsg.contains("PERMISSION_DENIED") || errorMsg.contains("permission")) {
							statusCode = 403;
						} else if (errorMsg.contains("READ_ONLY")) {
							statusCode = 403;
						}
					}
					
					log.error("Upload WRITE failed for {}: {}", tempPath, errorMsg, writeError);
					// Try to clean up temp file on failure
					if (useTempFile) {
						folderOps.delete(tempPath).exceptionally(deleteError -> {
							log.warn("Failed to clean up temp file {}: {}", tempPath, deleteError.getMessage());
							return null;
						});
					}
					handleAsyncError(asyncContext, servletResponse, 
							errorMsg != null ? errorMsg : "Upload failed", statusCode);
				} else if (useTempFile) {
					// WRITE succeeded, now MOVE temp file to final destination
					log.debug("WRITE to temp file succeeded, moving {} -> {}", tempPath, finalPath);
					
					folderOps.move(tempPath, finalPath).whenComplete((moveResult, moveError) -> {
						if (moveError != null) {
							String errorMsg = moveError.getMessage();
							log.error("Upload MOVE failed for {} -> {}: {}", tempPath, finalPath, errorMsg, moveError);
							// Try to clean up temp file
							folderOps.delete(tempPath).exceptionally(deleteError -> {
								log.warn("Failed to clean up temp file {}: {}", tempPath, deleteError.getMessage());
								return null;
							});
							handleAsyncError(asyncContext, servletResponse, 
									"Upload failed during move: " + (errorMsg != null ? errorMsg : "unknown error"), 500);
						} else {
							// Success - return the move result
							try {
								servletResponse.setContentType("application/json");
								servletResponse.setCharacterEncoding("UTF-8");
								servletResponse.setStatus(200);
								// Return info about the final file
								JSONObject result = new JSONObject();
								result.put("success", true);
								result.put("path", finalPath);
								result.put("bytesWritten", contentLength);
								servletResponse.getWriter().write(result.toString());
								completeAsyncContext(asyncContext);
							} catch (IOException e) {
								log.error("Error sending upload response: {}", e.getMessage(), e);
								handleAsyncError(asyncContext, servletResponse, "Internal error", 500);
							}
						}
					});
				} else {
					// Append mode - no temp file, return WRITE result directly
					try {
						servletResponse.setContentType("application/json");
						servletResponse.setCharacterEncoding("UTF-8");
						servletResponse.setStatus(200);
						servletResponse.getWriter().write(writeResult.toString());
						completeAsyncContext(asyncContext);
					} catch (IOException e) {
						log.error("Error sending upload response: {}", e.getMessage(), e);
						handleAsyncError(asyncContext, servletResponse, "Internal error", 500);
					}
				}
			});
		});
	}

	private void handleFolderMkdir(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			FolderTunnel folderTunnel, String path) throws IOException {
		// Check read-only mode
		if (folderTunnel.isReadOnly()) {
			log.warn("Mkdir rejected: folder tunnel {} is read-only", folderTunnel.getTargetId());
			servletResponse.sendError(403, "Folder is read-only");
			return;
		}
		
		log.debug("MKDIR operation for tunnel {} path: {}", folderTunnel.getTargetId(), path);
		
		// Start async processing
		final jakarta.servlet.AsyncContext asyncContext = servletRequest.startAsync();
		asyncContext.setTimeout(0);
		
		// Use FolderOperations abstraction
		FolderOperations folderOps = new FolderOperations(folderTunnel, jWSSession);
		
		// Handle result future
		folderOps.mkdir(path).whenComplete((jsonResult, error) -> {
			if (error != null) {
				String errorMsg = error.getMessage();
				int statusCode = 500;
				
				if (errorMsg != null) {
					if (errorMsg.contains("ALREADY_EXISTS") || errorMsg.contains("already exists")) {
						statusCode = 409; // Conflict
					} else if (errorMsg.contains("NOT_FOUND") || errorMsg.contains("not found") || errorMsg.contains("parent")) {
						statusCode = 404;
					} else if (errorMsg.contains("PERMISSION_DENIED") || errorMsg.contains("permission")) {
						statusCode = 403;
					} else if (errorMsg.contains("READ_ONLY")) {
						statusCode = 403;
					}
				}
				
				log.error("Error creating directory {}: {}", path, errorMsg, error);
				handleAsyncError(asyncContext, servletResponse, 
						errorMsg != null ? errorMsg : "Error creating directory", statusCode);
			} else {
				try {
					servletResponse.setContentType("application/json");
					servletResponse.setStatus(201); // Created
					servletResponse.getWriter().write(jsonResult.toString());
					completeAsyncContext(asyncContext);
				} catch (IOException e) {
					log.error("Error sending MKDIR response: {}", e.getMessage(), e);
					handleAsyncError(asyncContext, servletResponse, "Internal error", 500);
				}
			}
		});
	}

	private void handleFolderDelete(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			FolderTunnel folderTunnel, String path) throws IOException {
		// Check read-only mode
		if (folderTunnel.isReadOnly()) {
			log.warn("Delete rejected: folder tunnel {} is read-only", folderTunnel.getTargetId());
			servletResponse.sendError(403, "Folder is read-only");
			return;
		}
		
		log.debug("DELETE operation for tunnel {} path: {}", folderTunnel.getTargetId(), path);
		
		// Start async processing
		final jakarta.servlet.AsyncContext asyncContext = servletRequest.startAsync();
		asyncContext.setTimeout(0);
		
		// Use FolderOperations abstraction
		FolderOperations folderOps = new FolderOperations(folderTunnel, jWSSession);
		
		// Handle result future
		folderOps.delete(path).whenComplete((jsonResult, error) -> {
			if (error != null) {
				String errorMsg = error.getMessage();
				int statusCode = 500;
				
				if (errorMsg != null) {
					if (errorMsg.contains("NOT_FOUND") || errorMsg.contains("not found")) {
						statusCode = 404;
					} else if (errorMsg.contains("PERMISSION_DENIED") || errorMsg.contains("permission")) {
						statusCode = 403;
					} else if (errorMsg.contains("IS_DIRECTORY")) {
						statusCode = 400;
					} else if (errorMsg.contains("READ_ONLY")) {
						statusCode = 403;
					}
				}
				
				log.error("Error deleting {}: {}", path, errorMsg, error);
				handleAsyncError(asyncContext, servletResponse, 
						errorMsg != null ? errorMsg : "Error deleting file", statusCode);
			} else {
				try {
					servletResponse.setContentType("application/json");
					servletResponse.setStatus(200);
					servletResponse.getWriter().write(jsonResult.toString());
					completeAsyncContext(asyncContext);
				} catch (IOException e) {
					log.error("Error sending DELETE response: {}", e.getMessage(), e);
					handleAsyncError(asyncContext, servletResponse, "Internal error", 500);
				}
			}
		});
	}

	private void handleFolderRmdir(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			FolderTunnel folderTunnel, String path) throws IOException {
		// Check read-only mode
		if (folderTunnel.isReadOnly()) {
			log.warn("Rmdir rejected: folder tunnel {} is read-only", folderTunnel.getTargetId());
			servletResponse.sendError(403, "Folder is read-only");
			return;
		}
		
		log.debug("RMDIR operation for tunnel {} path: {}", folderTunnel.getTargetId(), path);
		
		// Start async processing
		final jakarta.servlet.AsyncContext asyncContext = servletRequest.startAsync();
		asyncContext.setTimeout(0);
		
		// Use FolderOperations abstraction
		FolderOperations folderOps = new FolderOperations(folderTunnel, jWSSession);
		
		// Handle result future
		folderOps.rmdir(path).whenComplete((jsonResult, error) -> {
			if (error != null) {
				String errorMsg = error.getMessage();
				int statusCode = 500;
				
				if (errorMsg != null) {
					if (errorMsg.contains("NOT_FOUND") || errorMsg.contains("not found")) {
						statusCode = 404;
					} else if (errorMsg.contains("NOT_EMPTY") || errorMsg.contains("not empty") || errorMsg.contains("Directory not empty")) {
						statusCode = 409; // Conflict - directory not empty
					} else if (errorMsg.contains("NOT_A_DIRECTORY") || errorMsg.contains("not a directory")) {
						statusCode = 400;
					} else if (errorMsg.contains("PERMISSION_DENIED") || errorMsg.contains("permission")) {
						statusCode = 403;
					} else if (errorMsg.contains("READ_ONLY")) {
						statusCode = 403;
					}
				}
				
				log.error("Error removing directory {}: {}", path, errorMsg, error);
				handleAsyncError(asyncContext, servletResponse, 
						errorMsg != null ? errorMsg : "Error removing directory", statusCode);
			} else {
				try {
					servletResponse.setContentType("application/json");
					servletResponse.setStatus(200);
					servletResponse.getWriter().write(jsonResult.toString());
					completeAsyncContext(asyncContext);
				} catch (IOException e) {
					log.error("Error sending RMDIR response: {}", e.getMessage(), e);
					handleAsyncError(asyncContext, servletResponse, "Internal error", 500);
				}
			}
		});
	}

	private void handleFolderMove(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			FolderTunnel folderTunnel, String path) throws IOException {
		// Check read-only mode
		if (folderTunnel.isReadOnly()) {
			log.warn("Move rejected: folder tunnel {} is read-only", folderTunnel.getTargetId());
			servletResponse.sendError(403, "Folder is read-only");
			return;
		}
		
		// Get destination path from request body
		String newPath = null;
		try {
			StringBuilder body = new StringBuilder();
			try (java.io.BufferedReader reader = servletRequest.getReader()) {
				String line;
				while ((line = reader.readLine()) != null) {
					body.append(line);
				}
			}
			if (body.length() > 0) {
				JSONObject requestBody = new JSONObject(body.toString());
				newPath = requestBody.optString("path", null);
			}
		} catch (Exception e) {
			log.warn("Error parsing move request body: {}", e.getMessage());
		}
		
		if (newPath == null || newPath.isEmpty()) {
			servletResponse.sendError(400, "Missing destination path in request body");
			return;
		}
		
		log.debug("MOVE operation for tunnel {} path: {} -> {}", folderTunnel.getTargetId(), path, newPath);
		
		// Start async processing
		final jakarta.servlet.AsyncContext asyncContext = servletRequest.startAsync();
		asyncContext.setTimeout(0);
		
		// Use FolderOperations abstraction
		FolderOperations folderOps = new FolderOperations(folderTunnel, jWSSession);
		final String destPath = newPath;
		
		// Handle result future
		folderOps.move(path, destPath).whenComplete((jsonResult, error) -> {
			if (error != null) {
				String errorMsg = error.getMessage();
				int statusCode = 500;
				
				if (errorMsg != null) {
					if (errorMsg.contains("NOT_FOUND") || errorMsg.contains("not found")) {
						statusCode = 404;
					} else if (errorMsg.contains("ALREADY_EXISTS") || errorMsg.contains("already exists")) {
						statusCode = 409; // Conflict
					} else if (errorMsg.contains("PERMISSION_DENIED") || errorMsg.contains("permission")) {
						statusCode = 403;
					} else if (errorMsg.contains("READ_ONLY")) {
						statusCode = 403;
					} else if (errorMsg.contains("INVALID_PATH") || errorMsg.contains("invalid")) {
						statusCode = 400;
					}
				}
				
				log.error("Error moving {} to {}: {}", path, destPath, errorMsg, error);
				handleAsyncError(asyncContext, servletResponse, 
						errorMsg != null ? errorMsg : "Error moving file", statusCode);
			} else {
				try {
					servletResponse.setContentType("application/json");
					servletResponse.setStatus(200);
					servletResponse.getWriter().write(jsonResult.toString());
					completeAsyncContext(asyncContext);
				} catch (IOException e) {
					log.error("Error sending MOVE response: {}", e.getMessage(), e);
					handleAsyncError(asyncContext, servletResponse, "Internal error", 500);
				}
			}
		});
	}

	/**
	 * Handles errors during async servlet request processing by sending an error response
	 * and completing the async context.
	 * 
	 * @param asyncContext The Jakarta Servlet AsyncContext to complete
	 * @param servletResponse The HTTP response to send the error to
	 * @param message The error message to include in the response
	 * @param statusCode The HTTP status code for the error (e.g., 404, 500)
	 */
	private void handleAsyncError(jakarta.servlet.AsyncContext asyncContext,
			HttpServletResponse servletResponse,
			String message, int statusCode) {
		try {
			if (!servletResponse.isCommitted()) {
				servletResponse.sendError(statusCode, message);
			}
		} catch (IOException e) {
			log.error("Error sending error response: {}", e.getMessage());
		} finally {
			completeAsyncContext(asyncContext);
		}
	}

	/**
	 * Safely completes a Jakarta Servlet AsyncContext.
	 * 
	 * @param asyncContext The Jakarta Servlet AsyncContext to complete
	 */
	private void completeAsyncContext(jakarta.servlet.AsyncContext asyncContext) {
		try {
			asyncContext.complete();
		} catch (Exception e) {
			log.warn("Error completing async context: {}", e.getMessage());
		}
	}

	/**
	 * Gets the underlying JWSSession.
	 * 
	 * @return The JWSSession associated with this handler
	 */
	public JWSSession getSession() {
		return jWSSession;
	}
	
	/**
	 * Extract filename from path
	 */
	private String extractFilename(String path) {
		if (path == null || path.isEmpty()) {
			return "download";
		}
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash >= 0 && lastSlash < path.length() - 1) {
			return path.substring(lastSlash + 1);
		}
		return path;
	}
		
	/**
	 * OutputStream wrapper that ensures servletResponse.flushBuffer() is called on flush(),
	 * so HTTP headers are sent immediately without waiting for data buffers to fill.
	 */
	private static class OutputStreamWrapper extends OutputStream {
		private final HttpServletResponse servletResponse;
		private final OutputStream delegate;
		
		public OutputStreamWrapper(HttpServletResponse servletResponse) throws IOException {
			this.servletResponse = servletResponse;
			this.delegate = servletResponse.getOutputStream();
		}
		
		@Override
		public void write(int b) throws IOException {
			delegate.write(b);
		}
		
		@Override
		public void write(byte[] b) throws IOException {
			delegate.write(b);
		}
		
		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			delegate.write(b, off, len);
		}
		
		@Override
		public void flush() throws IOException {
			delegate.flush();
			servletResponse.flushBuffer();
		}
		
		@Override
		public void close() throws IOException {
			delegate.close();
		}
	}
}

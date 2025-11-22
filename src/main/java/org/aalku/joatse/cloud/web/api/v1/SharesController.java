package org.aalku.joatse.cloud.web.api.v1;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.aalku.joatse.cloud.service.sharing.SharingManager;
import org.aalku.joatse.cloud.service.sharing.SharingManager.TunnelCreationResult;
import org.aalku.joatse.cloud.service.sharing.command.CommandTunnel;
import org.aalku.joatse.cloud.service.sharing.file.FileTunnel;
import org.aalku.joatse.cloud.service.sharing.http.HttpTunnel;
import org.aalku.joatse.cloud.service.sharing.request.LotSharingRequest;
import org.aalku.joatse.cloud.service.sharing.request.TunnelRequestCommandItem;
import org.aalku.joatse.cloud.service.sharing.request.TunnelRequestFileItem;
import org.aalku.joatse.cloud.service.sharing.request.TunnelRequestHttpItem;
import org.aalku.joatse.cloud.service.sharing.request.TunnelRequestItem;
import org.aalku.joatse.cloud.service.sharing.request.TunnelRequestTcpItem;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.service.sharing.shared.TcpTunnel;
import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/shares")
public class SharesController {
	
	private Logger log = LoggerFactory.getLogger(SharesController.class);
	
	@Autowired
	private SharingManager sharingManager;
	
	@Autowired
	private UserManager userManager;
	
	/**
	 * Get details of a sharing request by UUID (public endpoint)
	 */
	@GetMapping("/requests/{uuid}")
	public ResponseEntity<Map<String, Object>> getShareRequest(@PathVariable String uuid) {
		Map<String, Object> response = new LinkedHashMap<>();
		
		try {
			UUID requestUuid = UUID.fromString(uuid);
			LotSharingRequest request = sharingManager.getTunnelRequest(requestUuid);
			
			if (request == null) {
				response.put("error", "SHARE_REQUEST_NOT_FOUND");
				response.put("message", "Share request not found or expired");
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
			}
			
			// Build response with request details
			response.put("uuid", request.getUuid().toString());
			response.put("requesterAddress", request.getRequesterAddress().toString());
			response.put("creationTime", request.getCreationTime().toString());
			response.put("expiresAt", request.getCreationTime().plusSeconds(300).toString()); // 5 minutes
			response.put("autoAuthorizeByHttpUrl", false); // TODO: Get from request if available
			response.put("status", "PENDING");
			
			// Build items list
			List<Map<String, Object>> items = new ArrayList<>();
			for (TunnelRequestItem item : request.getItems()) {
				Map<String, Object> itemMap = new LinkedHashMap<>();
				
				if (item instanceof TunnelRequestHttpItem) {
					TunnelRequestHttpItem httpItem = (TunnelRequestHttpItem) item;
					itemMap.put("type", "http");
					itemMap.put("targetDescription", httpItem.targetDescription);
					itemMap.put("targetUrl", httpItem.getTargetUrl().toString());
					itemMap.put("targetHostname", httpItem.getTargetUrl().getHost());
					itemMap.put("targetPort", httpItem.getTargetUrl().getPort());
				} else if (item instanceof TunnelRequestFileItem) {
					TunnelRequestFileItem fileItem = (TunnelRequestFileItem) item;
					itemMap.put("type", "file");
					itemMap.put("targetDescription", fileItem.targetDescription);
					itemMap.put("targetPath", fileItem.getTargetPath());
				} else if (item instanceof TunnelRequestTcpItem) {
					TunnelRequestTcpItem tcpItem = (TunnelRequestTcpItem) item;
					itemMap.put("type", "tcp");
					itemMap.put("targetDescription", tcpItem.targetDescription);
					itemMap.put("targetHostname", tcpItem.targetHostname);
					itemMap.put("targetPort", tcpItem.targetPort);
				} else if (item instanceof TunnelRequestCommandItem) {
					TunnelRequestCommandItem cmdItem = (TunnelRequestCommandItem) item;
					itemMap.put("type", "command");
					itemMap.put("targetDescription", cmdItem.targetDescription);
					itemMap.put("command", cmdItem.getCommand());
				}
				
				items.add(itemMap);
			}
			response.put("items", items);
			
			log.debug("Retrieved share request: {}", requestUuid);
			return ResponseEntity.ok(response);
			
		} catch (IllegalArgumentException e) {
			response.put("error", "INVALID_UUID");
			response.put("message", "Invalid UUID format");
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
		} catch (Exception e) {
			response.put("error", "INTERNAL_ERROR");
			response.put("message", "Error retrieving share request: " + e.getMessage());
			log.error("Error retrieving share request: " + e, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}
	
	/**
	 * Confirm a sharing request (requires authentication)
	 */
	@PostMapping("/requests/{uuid}/confirm")
	public ResponseEntity<Map<String, Object>> confirmShareRequest(
			@PathVariable String uuid,
			@RequestBody(required = false) Map<String, Object> confirmRequest,
			HttpServletRequest httpRequest) {
		
		Map<String, Object> response = new LinkedHashMap<>();
		
		try {
			// Check authentication
			JoatseUser user = userManager.getAuthenticatedUser()
				.orElseThrow(() -> new IllegalStateException("User not authenticated"));
			
			userManager.requireRole("JOATSE_USER");
			
			UUID requestUuid = UUID.fromString(uuid);
			LotSharingRequest request = sharingManager.getTunnelRequest(requestUuid);
			
			if (request == null) {
				response.put("error", "SHARE_REQUEST_NOT_FOUND");
				response.put("message", "Share request not found or expired");
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
			}
			
			// Check if request is expired (5 minutes)
			if (request.getCreationTime().plusSeconds(300).isBefore(Instant.now())) {
				response.put("error", "SHARE_REQUEST_EXPIRED");
				response.put("message", "Share request has expired");
				return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
			}
			
			// Set allowed IP address (from request or auto-detect)
			InetAddress clientIp;
			if (confirmRequest != null && confirmRequest.containsKey("clientIpAddress")) {
				clientIp = InetAddress.getByName((String) confirmRequest.get("clientIpAddress"));
			} else {
				clientIp = getRemoteAddress(httpRequest);
			}
			
			// Set allowed addresses for the request
			List<String> allowedPatterns = new ArrayList<>();
			allowedPatterns.add(clientIp.getHostAddress());
			request.setAllowedAddressPatterns(allowedPatterns);
			
			// Accept the tunnel request
			sharingManager.acceptTunnelRequest(requestUuid, user);
			
			// Wait for the tunnel to be created (with timeout)
			TunnelCreationResult result = request.getFuture().get(30, TimeUnit.SECONDS);
			
			if (result instanceof TunnelCreationResult.Accepted) {
				TunnelCreationResult.Accepted accepted = (TunnelCreationResult.Accepted) result;
				SharedResourceLot tunnel = accepted.getTunnel();
				
				response.put("success", true);
				response.put("sessionUuid", tunnel.getUuid().toString());
				response.put("allowedIpAddresses", List.of(clientIp.getHostAddress()));
				
				// Build tunnels list
				List<Map<String, Object>> tunnels = new ArrayList<>();
				
				// HTTP tunnels
				for (HttpTunnel httpTunnel : tunnel.getHttpItems()) {
					Map<String, Object> tunnelMap = new LinkedHashMap<>();
					tunnelMap.put("type", "http");
					tunnelMap.put("targetId", String.valueOf(httpTunnel.getTargetId()));
					tunnelMap.put("listenUrl", httpTunnel.getListenUrl());
					tunnelMap.put("targetDescription", httpTunnel.getTargetDescription());
					tunnels.add(tunnelMap);
				}
				
				// File tunnels
				for (FileTunnel fileTunnel : tunnel.getFileItems()) {
					Map<String, Object> tunnelMap = new LinkedHashMap<>();
					tunnelMap.put("type", "file");
					tunnelMap.put("targetId", String.valueOf(fileTunnel.getTargetId()));
					
					// Get the listen URL - this should not be null after selectFileEndpoints is called
					URL listenUrl = fileTunnel.getListenUrl();
					if (listenUrl == null) {
						log.error("File tunnel listenUrl is null for targetId={}, targetDescription={}, targetPath={}. " +
							"ListenAddress={}", 
							fileTunnel.getTargetId(), fileTunnel.getTargetDescription(), 
							fileTunnel.getTargetPath(), fileTunnel.getListenAddress());
						// Return null to indicate the problem to the client
						tunnelMap.put("listenUrl", null);
					} else {
						tunnelMap.put("listenUrl", listenUrl.toString());
					}
					
					tunnelMap.put("targetDescription", fileTunnel.getTargetDescription());
					tunnelMap.put("targetPath", fileTunnel.getTargetPath());
					tunnels.add(tunnelMap);
				}
				
				// TCP tunnels
				for (TcpTunnel tcpTunnel : tunnel.getTcpItems()) {
					Map<String, Object> tunnelMap = new LinkedHashMap<>();
					tunnelMap.put("type", "tcp");
					tunnelMap.put("targetId", String.valueOf(tcpTunnel.targetId));
					tunnelMap.put("listenPort", tcpTunnel.getListenPort());
					tunnelMap.put("targetDescription", tcpTunnel.targetDescription);
					tunnels.add(tunnelMap);
				}
				
				// Command tunnels
				for (CommandTunnel cmdTunnel : tunnel.getCommandItems()) {
					Map<String, Object> tunnelMap = new LinkedHashMap<>();
					tunnelMap.put("type", "command");
					tunnelMap.put("targetId", String.valueOf(cmdTunnel.getTargetId()));
					tunnelMap.put("targetDescription", cmdTunnel.getTargetDescription());
					tunnels.add(tunnelMap);
				}
				
				response.put("tunnels", tunnels);
				
				log.info("Share request {} confirmed by user {}", requestUuid, user.getUsername());
				return ResponseEntity.ok(response);
			} else {
				response.put("error", "CONFIRMATION_FAILED");
				response.put("message", "Failed to confirm share request");
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
			}
			
		} catch (IllegalStateException e) {
			response.put("error", "AUTHENTICATION_REQUIRED");
			response.put("message", e.getMessage());
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		} catch (IllegalArgumentException e) {
			response.put("error", "INVALID_UUID");
			response.put("message", "Invalid UUID format");
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
		} catch (TimeoutException e) {
			response.put("error", "CONFIRMATION_TIMEOUT");
			response.put("message", "Tunnel creation timed out after 30 seconds");
			log.error("Tunnel creation timeout: " + e, e);
			return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(response);
		} catch (Exception e) {
			response.put("error", "CONFIRMATION_ERROR");
			response.put("message", "Error confirming share request: " + e.getMessage());
			log.error("Error confirming share request: " + e, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}
	
	/**
	 * Reject a sharing request (requires authentication)
	 */
	@PostMapping("/requests/{uuid}/reject")
	public ResponseEntity<Map<String, Object>> rejectShareRequest(@PathVariable String uuid) {
		Map<String, Object> response = new LinkedHashMap<>();
		
		try {
			// Check authentication
			userManager.getAuthenticatedUser()
				.orElseThrow(() -> new IllegalStateException("User not authenticated"));
			
			userManager.requireRole("JOATSE_USER");
			
			UUID requestUuid = UUID.fromString(uuid);
			
			// Reject the connection request
			sharingManager.rejectConnectionRequest(requestUuid, "User rejected");
			
			response.put("success", true);
			response.put("message", "Share request rejected");
			
			log.info("Share request {} rejected by user", requestUuid);
			return ResponseEntity.ok(response);
			
		} catch (IllegalStateException e) {
			response.put("error", "AUTHENTICATION_REQUIRED");
			response.put("message", e.getMessage());
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		} catch (IllegalArgumentException e) {
			response.put("error", "INVALID_UUID");
			response.put("message", "Invalid UUID format");
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
		} catch (Exception e) {
			response.put("error", "REJECTION_ERROR");
			response.put("message", "Error rejecting share request: " + e.getMessage());
			log.error("Error rejecting share request: " + e, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}
	
	private InetAddress getRemoteAddress(HttpServletRequest request) {
		try {
			return InetAddress.getByName(request.getRemoteAddr());
		} catch (UnknownHostException e) {
			throw new RuntimeException("Internal error", e);
		}
	}
}

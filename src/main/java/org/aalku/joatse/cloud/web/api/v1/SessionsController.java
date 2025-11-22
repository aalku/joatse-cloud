package org.aalku.joatse.cloud.web.api.v1;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.service.JWSSession;
import org.aalku.joatse.cloud.service.JoatseWsHandler;
import org.aalku.joatse.cloud.service.sharing.command.CommandTunnel;
import org.aalku.joatse.cloud.service.sharing.file.FileTunnel;
import org.aalku.joatse.cloud.service.sharing.http.HttpTunnel;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.service.sharing.shared.TcpTunnel;
import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionsController {
	
	private Logger log = LoggerFactory.getLogger(SessionsController.class);
	
	@Autowired
	private JoatseWsHandler wsHandler;
	
	@Autowired
	private ListenerConfigurationDetector webListenerConfiguration;
	
	@Autowired
	private UserManager userManager;
	
	/**
	 * List all active sessions for the authenticated user
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> listSessions(HttpServletRequest request) {
		Map<String, Object> response = new LinkedHashMap<>();
		
		try {
			JoatseUser user = userManager.getAuthenticatedUser()
				.orElseThrow(() -> new IllegalStateException("User not authenticated"));
			
			userManager.requireRole("JOATSE_USER");
			
			Collection<JWSSession> sessions = wsHandler.getSessions(user);
			List<Map<String, Object>> sessionsList = new ArrayList<>();
			
			for (JWSSession session : sessions) {
				Map<String, Object> sessionMap = new LinkedHashMap<>();
				SharedResourceLot tunnel = session.getSharedResourceLot();
				
				sessionMap.put("uuid", session.getTunnelUUID().toString());
				sessionMap.put("requesterAddress", tunnel.getRequesterAddress().toString());
				sessionMap.put("creationTime", tunnel.getCreationTime().toString());
				sessionMap.put("allowedAddresses", tunnel.getAllowedAddresses().stream()
					.map(addr -> addr.getHostAddress())
					.collect(Collectors.toList()));
				
				// Bandwidth information
				Map<String, Object> bandwidth = new LinkedHashMap<>();
				bandwidth.put("inKbps", session.getTrafficIn().getBps() / 1024.0);
				bandwidth.put("outKbps", session.getTrafficOut().getBps() / 1024.0);
				sessionMap.put("bandwidth", bandwidth);
				
				// Tunnel items organized by type
				Map<String, List<Map<String, Object>>> tunnels = new LinkedHashMap<>();
				
				// HTTP tunnels
				List<Map<String, Object>> httpList = new ArrayList<>();
				for (HttpTunnel httpTunnel : tunnel.getHttpItems()) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("targetId", String.valueOf(httpTunnel.getTargetId()));
					item.put("targetDescription", httpTunnel.getTargetDescription());
					item.put("targetUrl", httpTunnel.getTargetURL().toString());
					item.put("listenUrl", httpTunnel.getListenUrl());
					httpList.add(item);
				}
				tunnels.put("http", httpList);
				
				// File tunnels
				List<Map<String, Object>> fileList = new ArrayList<>();
				for (FileTunnel fileTunnel : tunnel.getFileItems()) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("targetId", String.valueOf(fileTunnel.getTargetId()));
					item.put("targetDescription", fileTunnel.getTargetDescription());
					item.put("targetPath", fileTunnel.getTargetPath());
					item.put("listenUrl", Optional.ofNullable(fileTunnel.getListenUrl())
						.map(URL::toString).orElse(null));
					fileList.add(item);
				}
				tunnels.put("file", fileList);
				
				// TCP tunnels
				List<Map<String, Object>> tcpList = new ArrayList<>();
				for (TcpTunnel tcpTunnel : tunnel.getTcpItems()) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("targetId", String.valueOf(tcpTunnel.targetId));
					item.put("targetDescription", tcpTunnel.targetDescription);
					item.put("targetHostname", tcpTunnel.targetHostname);
					item.put("targetPort", tcpTunnel.targetPort);
					item.put("listenHostname", webListenerConfiguration.getPublicHostnameTcp());
					item.put("listenPort", tcpTunnel.getListenPort());
					tcpList.add(item);
				}
				tunnels.put("tcp", tcpList);
				
				// Command tunnels
				List<Map<String, Object>> commandList = new ArrayList<>();
				for (CommandTunnel cmdTunnel : tunnel.getCommandItems()) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("targetId", String.valueOf(cmdTunnel.getTargetId()));
					item.put("targetDescription", cmdTunnel.getTargetDescription());
					item.put("targetHostname", cmdTunnel.getTargetHostname());
					item.put("targetPort", cmdTunnel.getTargetPort());
					item.put("targetUser", cmdTunnel.getTargetUser());
					item.put("command", Arrays.asList(cmdTunnel.getCommand()));
					commandList.add(item);
				}
				tunnels.put("command", commandList);
				
				sessionMap.put("tunnels", tunnels);
				sessionsList.add(sessionMap);
			}
			
			response.put("sessions", sessionsList);
			
			log.debug("Listed {} sessions for user {}", sessionsList.size(), user.getUsername());
			return ResponseEntity.ok(response);
			
		} catch (IllegalStateException e) {
			response.put("error", "AUTHENTICATION_REQUIRED");
			response.put("message", e.getMessage());
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		} catch (Exception e) {
			response.put("error", "ERROR_LISTING_SESSIONS");
			response.put("message", "Error listing sessions: " + e.getMessage());
			log.error("Error listing sessions: " + e, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}
	
	/**
	 * Update session access (add/remove IP addresses)
	 */
	@PutMapping("/{sessionUuid}/access")
	public ResponseEntity<Map<String, Object>> updateSessionAccess(
			@PathVariable String sessionUuid,
			@RequestBody Map<String, Object> accessRequest,
			HttpServletRequest request) {
		
		Map<String, Object> response = new LinkedHashMap<>();
		
		try {
			JoatseUser user = userManager.getAuthenticatedUser()
				.orElseThrow(() -> new IllegalStateException("User not authenticated (updateSessionAccess)"));
			
			userManager.requireRole("JOATSE_USER");
			
			UUID uuid = UUID.fromString(sessionUuid);
			Optional<SharedResourceLot> sessionOpt = getSession(uuid, user);
			
			if (sessionOpt.isEmpty()) {
				response.put("error", "SESSION_NOT_FOUND");
				response.put("message", "Session not found");
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
			}
			
			SharedResourceLot session = sessionOpt.get();
			String action = (String) accessRequest.get("action");
			
			if ("add_ip".equals(action)) {
				InetAddress ip;
				if (accessRequest.containsKey("ipAddress")) {
					ip = InetAddress.getByName((String) accessRequest.get("ipAddress"));
				} else {
					ip = getRemoteAddress(request);
				}
				
				// Handle localhost specially
				if (ip.isLoopbackAddress()) {
					for (InetAddress ip2 : InetAddress.getAllByName("localhost")) {
						session.addAllowedAddress(ip2);
					}
				} else {
					session.addAllowedAddress(ip);
				}
				
				response.put("success", true);
				response.put("allowedAddresses", session.getAllowedAddresses().stream()
					.map(addr -> addr.getHostAddress())
					.collect(Collectors.toList()));
				
				log.info("Added IP {} to session {}", ip.getHostAddress(), sessionUuid);
				return ResponseEntity.ok(response);
				
			} else if ("remove_ip".equals(action)) {
				// TODO: Implement remove_ip action
				response.put("error", "NOT_IMPLEMENTED");
				response.put("message", "Remove IP action not yet implemented");
				return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(response);
				
			} else if ("clear_all".equals(action)) {
				// TODO: Implement clear_all action
				response.put("error", "NOT_IMPLEMENTED");
				response.put("message", "Clear all action not yet implemented");
				return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(response);
				
			} else {
				response.put("error", "INVALID_ACTION");
				response.put("message", "Invalid action: " + action);
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
			}
			
		} catch (IllegalStateException e) {
			response.put("error", "AUTHENTICATION_REQUIRED");
			response.put("message", e.getMessage());
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		} catch (IllegalArgumentException e) {
			response.put("error", "INVALID_UUID");
			response.put("message", "Invalid UUID format");
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
		} catch (Exception e) {
			response.put("error", "ACCESS_UPDATE_ERROR");
			response.put("message", "Error updating session access: " + e.getMessage());
			log.error("Error updating session access: " + e, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}
	
	/**
	 * Disconnect a session
	 */
	@DeleteMapping("/{sessionUuid}")
	public ResponseEntity<Map<String, Object>> disconnectSession(@PathVariable String sessionUuid) {
		Map<String, Object> response = new LinkedHashMap<>();
		
		try {
			JoatseUser user = userManager.getAuthenticatedUser()
				.orElseThrow(() -> new IllegalStateException("User not authenticated (disconnectSession)"));
			
			userManager.requireRole("JOATSE_USER");
			
			UUID uuid = UUID.fromString(sessionUuid);
			Optional<SharedResourceLot> sessionOpt = getSession(uuid, user);
			
			if (sessionOpt.isEmpty()) {
				response.put("error", "SESSION_NOT_FOUND");
				response.put("message", "Session not found");
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
			}
			
			SharedResourceLot session = sessionOpt.get();
			wsHandler.closeSession(session, "Close requested by user through REST API");
			
			response.put("success", true);
			response.put("message", "Session disconnected");
			
			log.info("Session {} disconnected by user {}", sessionUuid, user.getUsername());
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
			response.put("error", "DISCONNECT_ERROR");
			response.put("message", "Error disconnecting session: " + e.getMessage());
			log.error("Error disconnecting session: " + e, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}
	
	private Optional<SharedResourceLot> getSession(UUID uuid, JoatseUser user) {
		return wsHandler.getSessions(user).stream()
			.map(s -> s.getSharedResourceLot())
			.filter(srl -> srl.getUuid().equals(uuid))
			.findAny();
	}
	
	private InetAddress getRemoteAddress(HttpServletRequest request) {
		try {
			return InetAddress.getByName(request.getRemoteAddr());
		} catch (UnknownHostException e) {
			throw new RuntimeException("Internal error", e);
		}
	}
}

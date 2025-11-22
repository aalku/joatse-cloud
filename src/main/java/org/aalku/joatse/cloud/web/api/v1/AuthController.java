package org.aalku.joatse.cloud.web.api.v1;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
	
	private Logger log = LoggerFactory.getLogger(AuthController.class);
	
	@Autowired
	private UserManager userManager;
	
	@Autowired(required = false)
	private AuthenticationManager authenticationManager;
	
	@PostMapping("/login")
	public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> loginRequest) {
		Map<String, Object> response = new LinkedHashMap<>();
		
		try {
			String username = (String) loginRequest.get("username");
			String password = (String) loginRequest.get("password");
			
			if (username == null || password == null) {
				response.put("success", false);
				response.put("error", "INVALID_REQUEST");
				response.put("message", "Username and password are required");
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
			}
			
			// Authenticate using Spring Security
			Authentication authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(username, password)
			);
			
			JoatseUser user = (JoatseUser) authentication.getPrincipal();
			
			// Generate JWT token
			String accessToken = userManager.generateJwt(user);
			
			response.put("success", true);
			response.put("accessToken", accessToken);
			response.put("tokenType", "Bearer");
			response.put("expiresIn", 1200); // 20 minutes
			
			// Add user information
			Map<String, Object> userInfo = new LinkedHashMap<>();
			userInfo.put("id", user.getUuid().toString());
			userInfo.put("username", user.getUsername());
			userInfo.put("roles", user.getAuthorities().stream()
				.map(a -> a.getAuthority())
				.collect(Collectors.toList()));
			response.put("user", userInfo);
			
			// For now, also return refreshToken (same as accessToken for simplicity)
			// TODO: Implement proper refresh token
			response.put("refreshToken", accessToken);
			response.put("refreshExpiresIn", 604800); // 7 days
			
			log.info("User {} logged in successfully via REST API", username);
			
			return ResponseEntity.ok(response);
			
		} catch (BadCredentialsException e) {
			response.put("success", false);
			response.put("error", "INVALID_CREDENTIALS");
			response.put("message", "Invalid username or password");
			log.warn("Failed login attempt: {}", e.getMessage());
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		} catch (Exception e) {
			response.put("success", false);
			response.put("error", "AUTHENTICATION_ERROR");
			response.put("message", "Authentication error: " + e.getMessage());
			log.error("Authentication error: " + e, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}
	
	@PostMapping("/refresh")
	public ResponseEntity<Map<String, Object>> refresh() {
		Map<String, Object> response = new LinkedHashMap<>();
		
		try {
			// Get authenticated user from JWT
			JoatseUser user = userManager.getAuthenticatedUser()
				.orElseThrow(() -> new RuntimeException("User not authenticated"));
			
			// Generate new JWT token
			String accessToken = userManager.generateJwt(user);
			
			response.put("accessToken", accessToken);
			response.put("tokenType", "Bearer");
			response.put("expiresIn", 1200); // 20 minutes
			response.put("refreshToken", accessToken);
			response.put("refreshExpiresIn", 604800); // 7 days
			
			log.info("Token refreshed for user {}", user.getUsername());
			
			return ResponseEntity.ok(response);
			
		} catch (Exception e) {
			response.put("error", "REFRESH_TOKEN_EXPIRED");
			response.put("message", "Refresh token has expired, please login again");
			log.error("Token refresh error: " + e, e);
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		}
	}
}

package org.aalku.joatse.cloud.web.jwt;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JWTAuthorizationFilter extends BasicAuthenticationFilter {
	
	private Logger log = LoggerFactory.getLogger(JWTAuthorizationFilter.class);

	public static final String COOKIE_JWT_KEY = "jwt";

	@Autowired
	private JoatseTokenManager joatseTokenManager;
	
	@Autowired
	private UserManager userManager;

	public JWTAuthorizationFilter() {
		super(a -> {
			throw new UnsupportedOperationException();
		});
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		String jwtToken = null;
		
		// First, check for Bearer token in Authorization header (for REST API)
		String authHeader = request.getHeader("Authorization");
		if (authHeader != null && authHeader.startsWith("Bearer ")) {
			jwtToken = authHeader.substring(7);
			log.debug("Found JWT token in Authorization header");
		}
		
		// Fallback to JWT cookie (for web UI)
		if (jwtToken == null) {
			Optional<Cookie> oCookie = Optional.ofNullable(request.getCookies()).map((Cookie[] a) -> Arrays.asList(a))
					.orElse(Collections.emptyList()).stream().filter(c -> c.getName().equals(COOKIE_JWT_KEY))
					.filter(c -> c.getValue().length() > 0).findAny();
			if (oCookie.isPresent()) {
				jwtToken = oCookie.get().getValue();
				log.debug("Found JWT token in cookie");
			}
		}
		
		// If we have a JWT token (from either source), validate and authenticate
		if (jwtToken != null) {
			try {
				Map<String, Object> tokenDetails = joatseTokenManager.verifyToken(jwtToken);
				if (tokenDetails != null) {
					JoatseUser user = userManager.loadUserByUUID(UUID.fromString((String)tokenDetails.get(JoatseUser.ATTRIB_KEY_UUID)));
					UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
					SecurityContextHolder.getContext().setAuthentication(authentication);
					log.debug("Authenticated user {} via JWT", user.getUsername());
				}
			} catch (JwtException e) {
				log.warn("Exception processing jwt: " + e, e);
			}
		}
		
        chain.doFilter(request, response);
    }
}
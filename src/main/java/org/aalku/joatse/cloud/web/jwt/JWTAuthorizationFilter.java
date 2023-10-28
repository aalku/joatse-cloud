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

		Optional<Cookie> oCookie = Optional.ofNullable(request.getCookies()).map((Cookie[] a) -> Arrays.asList(a))
				.orElse(Collections.emptyList()).stream().filter(c -> c.getName().equals(COOKIE_JWT_KEY))
				.filter(c -> c.getValue().length() > 0).findAny();
    	if (oCookie.isPresent()) {
    		Cookie cookie = oCookie.get();
			try {
				Map<String, Object> tokenDetails = joatseTokenManager.verifyToken(cookie.getValue());
				if (tokenDetails != null) {
					JoatseUser user = userManager.loadUserByUUID(UUID.fromString((String)tokenDetails.get(JoatseUser.ATTRIB_KEY_UUID)));
					UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
					SecurityContextHolder.getContext().setAuthentication(authentication);
				}
			} catch (JwtException e) {
				log.warn("Exception processing jwt: " + e, e);
			}
    	}
        chain.doFilter(request, response);
    }
}
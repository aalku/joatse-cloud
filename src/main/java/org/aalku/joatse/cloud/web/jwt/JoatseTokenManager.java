package org.aalku.joatse.cloud.web.jwt;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;

@Component
public class JoatseTokenManager implements TokenVerifier, InitializingBean {

	private static final String ISSUER = "joatse";

	private Logger log = LoggerFactory.getLogger(JoatseTokenManager.class);
	
	@Value("${cloud.jwt.secret:}")
	private String secretKeyString;

	private SecretKey signingKey;

	private JwtParser parser;

	@Value("${cloud.jwt.duration.millis:1200000}")
	private long tokenDurationMillis;

	@Value("${cloud.jwt.refresh.millis:300000}")
	private long tokenRefreshMillis;

	public JoatseTokenManager() {
	}
	
	@Override
	public Map<String, Object> verifyToken(String token) {
		try {
			Claims claims = (Claims)parser.parse(token).getBody();
			log.info("Verified token {}", claims);
			return claims;
		} catch (SignatureException | ExpiredJwtException | MalformedJwtException | IllegalArgumentException | ClassCastException e) {
			log.warn("Invalid token: " + token);
			return null;
		}
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		if (secretKeyString != null || !secretKeyString.isEmpty()) {
			try {
				signingKey = Keys.hmacShaKeyFor(secretKeyString.getBytes(StandardCharsets.UTF_8));
			} catch (WeakKeyException e) {
				log.warn("cloud.jwt.secret is to short (min 256 bytes, 512 recomended) so a random one will be used");
			}
		} else {
			log.warn("cloud.jwt.secret was not specified (min 256 bytes, 512 recomended) so a random one will be used");
		}
		if (signingKey == null) {
			signingKey = Keys.secretKeyFor(SignatureAlgorithm.HS512);
		}
		
		parser = Jwts.parserBuilder().setSigningKey(signingKey).requireIssuer(ISSUER).build();
	}

	public String generateToken(Map<String, String> userDetails, List<String> authorities) {
		return Jwts.builder().signWith(signingKey).setIssuedAt(new Date()).setIssuer(ISSUER)
				.setExpiration(new Date(System.nanoTime() + tokenDurationMillis))
				.addClaims(new LinkedHashMap<>(userDetails)).compact();
	}

}

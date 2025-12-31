package org.aalku.joatse.cloud.web.jwt;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@Component
public class JoatseTokenManager implements TokenVerifier, InitializingBean {

	private static final String ISSUER = "joatse";
	private static final int MIN_KEY_SIZE_BYTES = 64; // 512 bits for HS512

	private Logger log = LoggerFactory.getLogger(JoatseTokenManager.class);
	
	@Value("${cloud.jwt.secret:}")
	private String secretKeyString;

	private SecretKey signingKey;

	private JWSSigner signer;
	private JWSVerifier verifier;

	@Value("${cloud.jwt.duration.millis:1200000}")
	private long tokenDurationMillis;

	@Value("${cloud.jwt.refresh.millis:300000}")
	private long tokenRefreshMillis;

	public JoatseTokenManager() {
	}
	
	@Override
	public Map<String, Object> verifyToken(String token) {
		try {
			SignedJWT signedJWT = SignedJWT.parse(token);
			
			// Verify signature
			if (!signedJWT.verify(verifier)) {
				log.warn("Invalid token signature: " + token);
				return null;
			}
			
			JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
			
			// Verify issuer
			if (!ISSUER.equals(claims.getIssuer())) {
				log.warn("Invalid token issuer: " + token);
				return null;
			}
			
			// Verify expiration
			Date expiration = claims.getExpirationTime();
			if (expiration != null && expiration.before(new Date())) {
				log.warn("Token expired: " + token);
				return null;
			}
			
			log.debug("Verified token {}", claims);
			return claims.getClaims();
		} catch (ParseException | JOSEException | IllegalArgumentException e) {
			log.warn("Invalid token: " + token);
			return null;
		}
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		byte[] keyBytes = null;
		
		if (secretKeyString != null && !secretKeyString.isEmpty()) {
			byte[] providedBytes = secretKeyString.getBytes(StandardCharsets.UTF_8);
			if (providedBytes.length >= MIN_KEY_SIZE_BYTES) {
				keyBytes = providedBytes;
			} else {
				log.warn("cloud.jwt.secret is too short (min {} bytes, {} provided) so a random one will be used", 
					MIN_KEY_SIZE_BYTES, providedBytes.length);
			}
		} else {
			log.warn("cloud.jwt.secret was not specified (min {} bytes) so a random one will be used", MIN_KEY_SIZE_BYTES);
		}
		
		if (keyBytes == null) {
			keyBytes = new byte[MIN_KEY_SIZE_BYTES];
			new SecureRandom().nextBytes(keyBytes);
		}
		
		signingKey = new SecretKeySpec(keyBytes, "HmacSHA512");
		signer = new MACSigner(signingKey);
		verifier = new MACVerifier(signingKey);
	}

	public String generateToken(Map<String, String> userDetails, List<String> authorities) {
		try {
			JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.issueTime(new Date())
				.expirationTime(new Date(System.currentTimeMillis() + tokenDurationMillis));
			
			// Add user details as claims
			for (Map.Entry<String, String> entry : userDetails.entrySet()) {
				claimsBuilder.claim(entry.getKey(), entry.getValue());
			}
			
			SignedJWT signedJWT = new SignedJWT(
				new JWSHeader(JWSAlgorithm.HS512),
				claimsBuilder.build());
			
			signedJWT.sign(signer);
			return signedJWT.serialize();
		} catch (JOSEException e) {
			throw new RuntimeException("Failed to generate JWT token", e);
		}
	}

}

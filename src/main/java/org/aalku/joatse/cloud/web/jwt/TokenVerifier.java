package org.aalku.joatse.cloud.web.jwt;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

import javax.annotation.Nullable;

public interface TokenVerifier {
	
	@Nullable
	Map<String, Object> verifyToken(String token) throws GeneralSecurityException, IOException;
	
}

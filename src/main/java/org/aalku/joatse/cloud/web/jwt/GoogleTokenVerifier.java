package org.aalku.joatse.cloud.web.jwt;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.apache.v2.ApacheHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

public class GoogleTokenVerifier implements TokenVerifier {

	private Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);
	private GoogleIdTokenVerifier internal;

	public GoogleTokenVerifier(Collection<String> clientIdList) {
		internal = new GoogleIdTokenVerifier.Builder(new ApacheHttpTransport(), new GsonFactory())
				.setAudience(clientIdList)
				.build();
	}
	
	@Override
	public Map<String, Object> verifyToken(String token) throws GeneralSecurityException, IOException {
		GoogleIdToken idToken = internal.verify(token);
		if (idToken != null) {
			Payload tokenPayload = idToken.getPayload();
			LinkedHashMap<String, Object> res = new LinkedHashMap<>(tokenPayload);
			log.info("Google User details: " + res);
			return res;
		} else {
			log.warn("Invalid Google ID token: " + token);
			return null;
		}
	}

}

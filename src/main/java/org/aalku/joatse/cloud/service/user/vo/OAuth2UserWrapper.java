package org.aalku.joatse.cloud.service.user.vo;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.aalku.joatse.cloud.service.user.UserManager;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class OAuth2UserWrapper implements OAuth2User, Supplier<JoatseUser>, Serializable {

	private static final long serialVersionUID = 1L;
	
	private final JoatseUser user;
	private final OAuth2User oauth2user;

	public OAuth2UserWrapper(JoatseUser user, OAuth2User oauth2user) {
		this.oauth2user = oauth2user;
		this.user = user;
	}

	public <A> A getAttribute(String name) {
		return oauth2user.getAttribute(name);
	}

	public Map<String, Object> getAttributes() {
		return oauth2user.getAttributes();
	}

	public String getName() {
		return oauth2user.getName();
	}

	/**
	 * Authorities of the JoatseUser (if any) and from oauth2.
	 * 
	 * If there is not JoatseUser then NOT_USER will be added.
	 * 
	 */
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return Stream
				.concat(oauth2user.getAuthorities().stream(),
						user == null ? Stream.of(UserManager.AUTHORITY_NOT_USER) : user.getAuthorities().stream())
				.collect(Collectors.toList());
	}

	@Override
	public JoatseUser get() {
		return user;
	}
}
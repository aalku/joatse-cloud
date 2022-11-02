package org.aalku.joatse.cloud.service.user;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.annotation.Transient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

public class JoatseUser implements OAuth2User, OidcUser, UserDetails {
	
	private static final String REGISTRATION_ID_LOCAL = "local";

	private static final long serialVersionUID = 1L;

	enum Type { SPRING, OAUTH2 }
	
	
	private String registrationId;
	private String principal_name;
	private String password;
	private String subId;
	private String email;
	
	@Transient
	private Collection<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();

	@Transient
	private OidcUser oidcUser;

	@Transient
	private OAuth2User oauth2User;
	
	public JoatseUser(UserDetails user) {
		this.registrationId = REGISTRATION_ID_LOCAL;
		this.subId = user.getUsername();
		this.password = user.getPassword();
		this.authorities.addAll(user.getAuthorities());
		this.principal_name = user.getUsername();
	}
	
	public JoatseUser(OAuth2User user, String registrationId) {
		this.registrationId = registrationId;
		this.authorities.addAll(user.getAuthorities());
		if (user instanceof OidcUser) {
			OidcUser u = (OidcUser) user;
			this.subId = u.getSubject();
			this.email = u.getEmail();
			this.principal_name = u.getName();
			this.oidcUser = u;
		} else {
			this.subId = Optional.ofNullable(user.getAttribute("subId")).map(o->o.toString()).orElse(null);
			this.email = Optional.ofNullable(user.getAttribute("email")).map(o->o.toString()).orElse(null);
			this.principal_name = user.getName();
			this.oauth2User = user;
		}
	}

	/** For testing and mock only */
	@Deprecated
	public JoatseUser(String registrationId, String principal_name) {
		this.registrationId = registrationId;
		this.subId = principal_name;
		this.password = null;
		this.principal_name = principal_name;
	}

	@Override
	public Map<String, Object> getAttributes() {
		return oauth2User != null ? oauth2User.getAttributes() : oidcUser != null ? oidcUser.getAttributes() : null;
	}
	
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return authorities;
	}

	@Override
	public String getName() {
		return principal_name;
	}

	@Override
	public String getPassword() {
		return password;
	}

	@Override
	public String getUsername() {
		return principal_name;
	}
	
	@Override
	public String getEmail() {
		return email;
	}
	
	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	@Override
	public boolean isAccountNonLocked() {
		return true;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	public Map<String, Object> getClaims() {
		return oidcUser != null ? oidcUser.getClaims() : Collections.emptyMap();
	}

	@Override
	public OidcUserInfo getUserInfo() {
		return oidcUser != null ? oidcUser.getUserInfo() : null;
	}

	@Override
	public OidcIdToken getIdToken() {
		return oidcUser != null ? oidcUser.getIdToken() : null;
	}
	
	public String getNameToAddress() {
		String name = oidcUser != null ? oidcUser.getGivenName()
				: oauth2User != null
						? Optional.ofNullable(oauth2User.getAttributes().get("given_name")).map(o -> o.toString())
								.orElse(getEmail())
						: getEmail();
		if (name == null) {
			name = getName();
		}
		return name;
	}

	@Override
	public int hashCode() {
		return Objects.hash(principal_name, registrationId);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		JoatseUser other = (JoatseUser) obj;
		return Objects.equals(principal_name, other.principal_name)
				&& Objects.equals(registrationId, other.registrationId);
	}

}

package org.aalku.joatse.cloud.service.user.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;

/**
 * Application user. 	
 * This reflects the database record only. OAuth2 details should be elsewhere.
 */
@Entity(name = "user")
public class JoatseUser implements UserDetails, Serializable {
	
	@Override
	public int hashCode() {
		return Objects.hash(getUuid());
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
		return Objects.equals(getUuid(), other.getUuid());
	}

	private static final long serialVersionUID = 1L;

	/**
	 * Random persistent ID
	 */
	@Id
	private UUID uuid;
	
	/**
	 * It's what you would need to enter in the login form to use it with a
	 * password. It can be an email or not.
	 */
	private String login;

	/**
	 * Ciphered password
	 */
	private String password;
	
	@ElementCollection(fetch = FetchType.EAGER)
	private Collection<String> grantedAuthoritiesList; 
	
	public static JoatseUser newLocalUser(String login) {
		JoatseUser user = new JoatseUser();
		user.uuid = UUID.randomUUID();
		user.login = login;
		user.password = null;
		user.grantedAuthoritiesList = new ArrayList<String>();
		user.grantedAuthoritiesList.add("ROLE_JOATSE_USER");
		return user;
	}
	
	private JoatseUser(UserDetails user) {
		this.login = user.getUsername();
		this.password = user.getPassword();
	}

	private JoatseUser() {
	}

	@Override
	public Collection<GrantedAuthority> getAuthorities() {
		synchronized (grantedAuthoritiesList) {
			return grantedAuthoritiesList.stream().map(x -> new SimpleGrantedAuthority(x))
					.collect(Collectors.toUnmodifiableList());
		}
	}
	
	public void addAuthority(SimpleGrantedAuthority authority) {
		synchronized (grantedAuthoritiesList) {
			grantedAuthoritiesList.add(authority.getAuthority());
		}
	}
	
	public void removeAuthority(SimpleGrantedAuthority authority) {
		synchronized (grantedAuthoritiesList) {
			grantedAuthoritiesList.remove(authority.getAuthority());
		}
	}

	@Override
	public String getPassword() {
		return password;
	}

	@Override
	public String getUsername() {
		return login;
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

	public void setPassword(String password) {
		this.password = password;
	}

	public UUID getUuid() {
		return uuid;
	}

	public void setUserName(String username) {
		this.login = username;
	}

}

package org.aalku.joatse.cloud.service.user;

import java.util.UUID;

import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;

public interface JoatseUserDetailsManager extends UserDetailsManager {
	public void deleteUser(UserDetails user);

	@Override
	default public boolean userExists(String username) {
		throw new UnsupportedOperationException();
	}

	@Override
	default public void deleteUser(String username) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	default public void changePassword(String oldPassword, String newPassword) {
		throw new UnsupportedOperationException();
	}

	public JoatseUser loadUserByUUID(UUID uuid);

}

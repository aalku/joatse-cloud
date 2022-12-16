package org.aalku.joatse.cloud.service.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.service.user.vo.PasswordResetToken;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PasswordResetTokenRepository extends CrudRepository<PasswordResetToken, UUID> {
	
	Optional<PasswordResetToken> findByUser(JoatseUser user);
	
	Optional<PasswordResetToken> findById(UUID id);
	
}

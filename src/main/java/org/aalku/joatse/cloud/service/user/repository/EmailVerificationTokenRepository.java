package org.aalku.joatse.cloud.service.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.aalku.joatse.cloud.service.user.vo.EmailVerificationToken;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailVerificationTokenRepository extends CrudRepository<EmailVerificationToken, UUID> {
	
	Optional<EmailVerificationToken> findByUser(JoatseUser user);
	
	Optional<EmailVerificationToken> findById(UUID id);
	
}

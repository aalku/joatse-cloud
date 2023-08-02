package org.aalku.joatse.cloud.service.user.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.service.user.vo.PreconfirmedShare;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PreconfirmedSharesRepository extends CrudRepository<PreconfirmedShare, UUID> {
	
	Collection<PreconfirmedShare> findByOwner(JoatseUser user);
	
	Optional<PreconfirmedShare> findById(UUID id);
	
}

package org.aalku.joatse.cloud.service.user.repository;

import java.util.UUID;

import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends CrudRepository<JoatseUser, UUID> {
	JoatseUser findByLogin(String login);
}

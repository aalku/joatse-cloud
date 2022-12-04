package org.aalku.joatse.cloud.service.user.repository;

import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.service.user.vo.OAuth2IssSubAccount;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OAuth2IssSubAccountRepository extends CrudRepository<OAuth2IssSubAccount, JoatseUser> {
	OAuth2IssSubAccount findByIssAndSub(String iss, String sub);
}

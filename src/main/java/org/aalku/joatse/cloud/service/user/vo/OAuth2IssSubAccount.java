package org.aalku.joatse.cloud.service.user.vo;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * This entity represents an oauth2 account identified by iss and sub
 * parameters, linked to a JoatseUser.
 * 
 * Not all oauth2 authentication providers use iss+sub. Other kind will use
 * other entities to link them to the JoatseUser.
 */
@Entity(name = "oauth2IssSubAccount")
public class OAuth2IssSubAccount implements Serializable {

	private static final long serialVersionUID = 1L;

	public static OAuth2IssSubAccount create(JoatseUser user, String iss, String sub) {
		OAuth2IssSubAccount x = new OAuth2IssSubAccount();
		x.id = UUID.randomUUID();
		x.user = user;
		x.iss = iss;
		x.sub = sub;
		return x;
	}
	
	@Id
	private UUID id; 
	
	@ManyToOne(targetEntity = JoatseUser.class)
	private JoatseUser user;
	
	private String iss;

	private String sub;

	public String getIss() {
		return iss;
	}

	public String getSub() {
		return sub;
	}

	public JoatseUser getUser() {
		return user;
	}
}

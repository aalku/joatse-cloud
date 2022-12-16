package org.aalku.joatse.cloud.service.user.vo;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 
 */
@Entity(name = "passwordResetToken")
@Table(uniqueConstraints = @UniqueConstraint(columnNames = { "user" }))
public class PasswordResetToken implements Serializable {
	
	private static final long serialVersionUID = 1L;

	/**
	 * Random persistent ID
	 */
	@Id
	private UUID uuid;
	
	@JoinColumn(name = "user")
	@ManyToOne(targetEntity = JoatseUser.class)
	private JoatseUser user;
	
	@Column
	private Instant creationTime;
	
	public PasswordResetToken() {
	}
	
	public PasswordResetToken(JoatseUser user) {
		this.user = user;
		this.uuid = UUID.randomUUID();
		this.creationTime = Instant.now();
	}

	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public JoatseUser getUser() {
		return user;
	}

	public void setUser(JoatseUser user) {
		this.user = user;
	}

	public Instant getCreationTime() {
		return creationTime;
	}

	public void setCreationTime(Instant creationTime) {
		this.creationTime = creationTime;
	}
}

package org.aalku.joatse.cloud.service.user.vo;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity(name = "preconfirmedShares")
@Table(indexes = { @Index(columnList = "owner", name = "preconfirmedShares_by_owner") })
public class PreconfirmedShare {
	@Id
	private UUID uuid;
	
	@JoinColumn(name = "owner")
	@ManyToOne(targetEntity = JoatseUser.class)
	private JoatseUser owner;
	
	@Column(name = "resources", length = 1024 * 64)
	private String resources;

	@Column(name = "requesterAddress")
	private String requesterAddress;

	@ElementCollection(fetch = FetchType.EAGER)
	private Set<String> allowedAddresses;

	@Column(name = "autoAuthorizeByHttpUrl")
	private Boolean autoAuthorizeByHttpUrl = false;
	
	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public JoatseUser getOwner() {
		return owner;
	}

	public void setOwner(JoatseUser user) {
		this.owner = user;
	}

	public String getResources() {
		return resources;
	}

	public void setResources(String resources) {
		this.resources = resources;
	}
	
	public InetSocketAddress getRequesterAddress() {
		if (requesterAddress == null || requesterAddress.isEmpty()) {
			return null;
		}
		String[] s = requesterAddress.split(":",-1);
		return new InetSocketAddress(s[0], Integer.valueOf(s[1]));
	}

	public void setRequesterAddress(String requesterAddress) {
		this.requesterAddress = requesterAddress;
	}

	public void setAllowedAddresses(Collection<InetAddress> allowedAddress) {
		this.allowedAddresses = allowedAddress.stream().map(a->a.getHostAddress()).collect(Collectors.toSet());
	}

	public Set<InetAddress> getAllowedAddresses() {
		return allowedAddresses == null ? null : allowedAddresses.stream()
			.filter(addr -> !isFlexiblePattern(addr)) // Skip flexible patterns that can't be converted to InetAddress
			.map(a->{
				try {
					return InetAddress.getByName(a);
				} catch (UnknownHostException e) {
					throw new RuntimeException("Invalid IP address: " + a, e);
				}
			}).collect(Collectors.toSet());
	}
	
	/**
	 * Gets the allowed address patterns as strings (supports flexible patterns like CIDR and wildcards)
	 */
	public Set<String> getAllowedAddressPatterns() {
		return allowedAddresses == null ? null : new LinkedHashSet<>(allowedAddresses);
	}
	
	/**
	 * Sets the allowed address patterns as strings (supports flexible patterns like CIDR and wildcards)
	 */
	public void setAllowedAddressPatterns(Collection<String> addressPatterns) {
		this.allowedAddresses = addressPatterns == null ? null : new LinkedHashSet<>(addressPatterns);
	}
	
	private boolean isFlexiblePattern(String address) {
		return "*".equals(address) || address.contains("/");
	}

	public static PreconfirmedShare fromSharedResourceLot(SharedResourceLot o) {
		PreconfirmedShare x = new PreconfirmedShare();
		x.setUuid(UUID.randomUUID());
		x.setOwner(o.getOwner());
		x.setResources(o.toJsonSharedResources().toString(0));
		x.setRequesterAddress(
				o.getRequesterAddress().getAddress().getHostAddress() + ":" + o.getRequesterAddress().getPort());
		// Don't save those from o, different meaning
		x.setAllowedAddresses(new LinkedHashSet<>());
		x.setAutoAuthorizeByHttpUrl(o.isAutoAuthorizeByHttpUrl());
		return x;
	}

	public boolean isAutoAuthorizeByHttpUrl() {
		return Optional.ofNullable(autoAuthorizeByHttpUrl).orElse(false);
	}

	public void setAutoAuthorizeByHttpUrl(boolean autoAuthorizeByHttpUrl) {
		this.autoAuthorizeByHttpUrl = autoAuthorizeByHttpUrl;
	}
}

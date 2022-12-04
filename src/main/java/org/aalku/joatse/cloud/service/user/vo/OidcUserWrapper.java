package org.aalku.joatse.cloud.service.user.vo;

import java.io.Serializable;
import java.net.URL;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.aalku.joatse.cloud.service.user.UserManager;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.AddressStandardClaim;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class OidcUserWrapper implements OidcUser, Supplier<JoatseUser>, Serializable {

	private static final long serialVersionUID = 1L;
	
	private final OidcUser oidcUser;
	private final JoatseUser user;

	public OidcUserWrapper(JoatseUser user, OidcUser oidcUser) {
		this.oidcUser = oidcUser;
		this.user = user;
	}

	public <A> A getAttribute(String name) {
		return oidcUser.getAttribute(name);
	}

	public <T> T getClaim(String claim) {
		return oidcUser.getClaim(claim);
	}

	public Map<String, Object> getAttributes() {
		return oidcUser.getAttributes();
	}

	public URL getIssuer() {
		return oidcUser.getIssuer();
	}

	public String getName() {
		return oidcUser.getName();
	}

	public Collection<? extends GrantedAuthority> getAuthorities() {
		return Stream
				.concat(oidcUser.getAuthorities().stream(),
						user == null ? Stream.of(UserManager.AUTHORITY_NOT_USER) : user.getAuthorities().stream())
				.collect(Collectors.toList());
	}

	public String getFullName() {
		return oidcUser.getFullName();
	}

	public boolean hasClaim(String claim) {
		return oidcUser.hasClaim(claim);
	}

	public String getSubject() {
		return oidcUser.getSubject();
	}

	public String getGivenName() {
		return oidcUser.getGivenName();
	}

	public List<String> getAudience() {
		return oidcUser.getAudience();
	}

	public String getClaimAsString(String claim) {
		return oidcUser.getClaimAsString(claim);
	}

	public String getFamilyName() {
		return oidcUser.getFamilyName();
	}

	public Instant getExpiresAt() {
		return oidcUser.getExpiresAt();
	}

	public Map<String, Object> getClaims() {
		return oidcUser.getClaims();
	}

	public String getMiddleName() {
		return oidcUser.getMiddleName();
	}

	public Instant getIssuedAt() {
		return oidcUser.getIssuedAt();
	}

	public Boolean getClaimAsBoolean(String claim) {
		return oidcUser.getClaimAsBoolean(claim);
	}

	public OidcUserInfo getUserInfo() {
		return oidcUser.getUserInfo();
	}

	public String getNickName() {
		return oidcUser.getNickName();
	}

	public Instant getAuthenticatedAt() {
		return oidcUser.getAuthenticatedAt();
	}

	public OidcIdToken getIdToken() {
		return oidcUser.getIdToken();
	}

	public String getPreferredUsername() {
		return oidcUser.getPreferredUsername();
	}

	public String getNonce() {
		return oidcUser.getNonce();
	}

	public String getProfile() {
		return oidcUser.getProfile();
	}

	public String getAuthenticationContextClass() {
		return oidcUser.getAuthenticationContextClass();
	}

	public String getPicture() {
		return oidcUser.getPicture();
	}

	public Instant getClaimAsInstant(String claim) {
		return oidcUser.getClaimAsInstant(claim);
	}

	public String getWebsite() {
		return oidcUser.getWebsite();
	}

	public List<String> getAuthenticationMethods() {
		return oidcUser.getAuthenticationMethods();
	}

	public String getEmail() {
		return oidcUser.getEmail();
	}

	public String getAuthorizedParty() {
		return oidcUser.getAuthorizedParty();
	}

	public URL getClaimAsURL(String claim) {
		return oidcUser.getClaimAsURL(claim);
	}

	public Boolean getEmailVerified() {
		return oidcUser.getEmailVerified();
	}

	public String getAccessTokenHash() {
		return oidcUser.getAccessTokenHash();
	}

	public String getAuthorizationCodeHash() {
		return oidcUser.getAuthorizationCodeHash();
	}

	public String getGender() {
		return oidcUser.getGender();
	}

	public String getBirthdate() {
		return oidcUser.getBirthdate();
	}

	public Map<String, Object> getClaimAsMap(String claim) {
		return oidcUser.getClaimAsMap(claim);
	}

	public String getZoneInfo() {
		return oidcUser.getZoneInfo();
	}

	public String getLocale() {
		return oidcUser.getLocale();
	}

	public String getPhoneNumber() {
		return oidcUser.getPhoneNumber();
	}

	public Boolean getPhoneNumberVerified() {
		return oidcUser.getPhoneNumberVerified();
	}

	public AddressStandardClaim getAddress() {
		return oidcUser.getAddress();
	}

	public List<String> getClaimAsStringList(String claim) {
		return oidcUser.getClaimAsStringList(claim);
	}

	public Instant getUpdatedAt() {
		return oidcUser.getUpdatedAt();
	}

	@Override
	public JoatseUser get() {
		return user;
	}
}
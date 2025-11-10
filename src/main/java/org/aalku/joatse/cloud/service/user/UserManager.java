package org.aalku.joatse.cloud.service.user;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.config.WebSecurityConfiguration;
import org.aalku.joatse.cloud.service.user.repository.EmailVerificationTokenRepository;
import org.aalku.joatse.cloud.service.user.repository.OAuth2IssSubAccountRepository;
import org.aalku.joatse.cloud.service.user.repository.PasswordResetTokenRepository;
import org.aalku.joatse.cloud.service.user.repository.UserRepository;
import org.aalku.joatse.cloud.service.user.vo.EmailVerificationToken;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.service.user.vo.OAuth2IssSubAccount;
import org.aalku.joatse.cloud.service.user.vo.OAuth2UserWrapper;
import org.aalku.joatse.cloud.service.user.vo.OidcUserWrapper;
import org.aalku.joatse.cloud.service.user.vo.PasswordResetToken;
import org.aalku.joatse.cloud.tools.io.AsyncEmailSender;
import org.aalku.joatse.cloud.web.jwt.JoatseTokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Configuration
public class UserManager {

	public static final GrantedAuthority AUTHORITY_NOT_USER = new SimpleGrantedAuthority("NOT_USER");

	@Autowired
	private UserRepository userRepository;
	
	@Autowired
	private EmailVerificationTokenRepository emailVerificationTokenRepository;

	@Autowired
	private PasswordResetTokenRepository passwordResetTokenRepository;
	
	@Autowired
	private OAuth2IssSubAccountRepository oauth2IssSubAccountRepository;
	
	@Value("${account.autoregister.admin.emails:}")
	private List<String> adminMailAccounts;
	
	@Value("${account.autoregister.user.emails:}")
	private List<String> userMailAccounts;
	
	@Autowired
	private AsyncEmailSender asyncEmailSender;

	@Autowired
	private JoatseTokenManager joatseJwtTokenManager;

	private Logger log = LoggerFactory.getLogger(UserManager.class);

	@SuppressWarnings("unchecked")
	public Optional<JoatseUser> getAuthenticatedUser() {
		JoatseUser res = null;
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		Object principal = authentication.getPrincipal();
		if (principal instanceof JoatseUser) {
			res = (JoatseUser) principal;
		} else if (principal instanceof Supplier<?>) {
			res = ((Supplier<JoatseUser>)principal).get();
		}
		return Optional.ofNullable(res);
	}
	
	private void updateAuthenticatedUser() {
		getAuthenticatedUser().ifPresent(user -> {
			SecurityContextHolder.getContext()
					.setAuthentication(new PreAuthenticatedAuthenticationToken(user, null, user.getAuthorities()));
		});
	}

	public OAuth2UserService<OAuth2UserRequest, OAuth2User> getOAuth2UserService() {
		return new DefaultOAuth2UserService() {
			@Override
			public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
				OAuth2User oauth2User = super.loadUser(userRequest);
				JoatseUser joatseUser = searchLinkedLocalUser(oauth2User);
				return new OAuth2UserWrapper(joatseUser, oauth2User);
			}
		};
	}

	public OAuth2UserService<OidcUserRequest, OidcUser> getOidcUserService() {
		return new OidcUserService() {
			@Override
			public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
				OidcUser oidcUser = super.loadUser(userRequest);
				JoatseUser joatseUser = searchLinkedLocalUser(oidcUser);
				return new OidcUserWrapper(joatseUser, oidcUser);
			}
		};
	}
	
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	protected JoatseUser searchLinkedLocalUser(OAuth2User oauth2User) {
		String iss = Optional.ofNullable(oauth2User.getAttribute("iss")).map(o->o.toString()).orElse(null);
		String sub = oauth2User.getAttribute("sub");
		String email = Optional.ofNullable(oauth2User.getAttribute("email")).map(a -> (String) a)
				.filter(m -> !m.trim().isEmpty()).orElse(null);
		return findIssSubUser(iss, sub, email);
	}

	private JoatseUser findIssSubUser(String iss, String sub, String email) {
		if (iss != null && sub != null) {
			OAuth2IssSubAccount account = oauth2IssSubAccountRepository.findByIssAndSub(iss, sub);
			if (account != null && account.getUser() != null) {
				log.info("Account found: {}", account.getUser());
				return account.getUser();
			} else {
				if (email != null && adminMailAccounts.contains(email)) {
					JoatseUser user = userRepository.findByLogin(email);
					if (user == null) {
						user = JoatseUser.newLocalUser(email, false);
						userRepository.save(user);
					}
					if (!user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_JOATSE_ADMIN"))) {
						user.addAuthority(new SimpleGrantedAuthority("ROLE_JOATSE_ADMIN"));
						userRepository.save(user);
					}
					if (account == null) {
						account = OAuth2IssSubAccount.create(user, iss, sub);
					} else {
						account.setUser(user);
					}
					oauth2IssSubAccountRepository.save(account);
					log.info("Admin account linked: {} = {}", user, email);
					return user;
				} else if (email != null && userMailAccounts.contains(email)) {
					JoatseUser user = userRepository.findByLogin(email);
					if (user != null) {
						log.warn("email prerregistered as user but it exists as account: {}", email);
						return null; 
					}
					user = JoatseUser.newLocalUser(email, false);
					account = OAuth2IssSubAccount.create(user, iss, sub);					
					userRepository.save(user);
					oauth2IssSubAccountRepository.save(account); // Saves user too
					return user;
				}
			}
		}
		return null;
	}
	
	public JoatseUser findUserFromExternalToken(Map<String, Object> tokenDetails) {
		String iss = Optional.ofNullable(tokenDetails.get("iss")).map(o->o.toString()).orElse(null);
		String sub = (String) tokenDetails.get("sub");
		String email = Optional.ofNullable(tokenDetails.get("email")).map(a -> (String) a)
				.filter(m -> !m.trim().isEmpty()).orElse(null);
		return findIssSubUser(iss, sub, email);
	}

	@Bean
	JoatseUserDetailsManager userDetailsService() {
		return new JoatseUserDetailsManager() {
			@Override
			public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
				return userRepository.findByLogin(username);
			}

			@Override
			public void createUser(UserDetails user) {
				JoatseUser u = (JoatseUser) user;
				JoatseUser old = userRepository.findByLogin(u.getUsername());
				if (old != null) {
					throw new IllegalArgumentException("Already existing login: " + old.getUsername());
				}
				userRepository.save(u);
			}

			@Override
			public void updateUser(UserDetails user) {
				JoatseUser u = (JoatseUser) user;
				Optional<JoatseUser> old = userRepository.findById(u.getUuid());
				if (old.isEmpty()) {
					throw new IllegalArgumentException("User does not exist: " + u.getUsername());
				}
				userRepository.save(u);
			}

			@Override
			public void deleteUser(UserDetails user) {
				JoatseUser u = (JoatseUser) user;
				Optional<JoatseUser> old = userRepository.findById(u.getUuid());
				if (old.isEmpty()) {
					throw new IllegalArgumentException("User does not exist: " + u.getUsername());
				}
				userRepository.delete(u);
			}

			@Override
			public JoatseUser loadUserByUUID(UUID uuid) {
				return userRepository.findById(uuid).orElse(null);
			}
		};
	}



	public void requireRole(String role) {
		if (!hasRole(role)) {
			throw new RuntimeException("Unauthorized");
		}
	}

	public boolean hasRole(String role) {
		return getAuthenticatedUser().map(u -> u.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_" + role)))
				.orElse(false);
	}

	public UUID newEmailVerificationToken() {
		JoatseUser user = getAuthenticatedUser().get();
		emailVerificationTokenRepository.findByUser(user).ifPresent(t->emailVerificationTokenRepository.delete(t));
		EmailVerificationToken next = new EmailVerificationToken(user);
		emailVerificationTokenRepository.save(next);
		log.debug("Saved emailVerificationToken {} for user {}", next.getUuid(), next.getUser());
		return next.getUuid();
	}
	
	private UUID newPasswordResetToken(JoatseUser user) {
		passwordResetTokenRepository.findByUser(user).ifPresent(t->passwordResetTokenRepository.delete(t));
		PasswordResetToken next = new PasswordResetToken(user);
		passwordResetTokenRepository.save(next);
		log.debug("Saved passwordResetToken {} for user {}", next.getUuid(), next.getUser());
		return next.getUuid();
	}
	
	public String newPasswordResetLink(JoatseUser user, String linkBaseUrl) {
		try {
			return new URL(new URL(linkBaseUrl), WebSecurityConfiguration.PATH_PASSWORD_RESET + "?token=" + newPasswordResetToken(user)).toExternalForm();
		} catch (MalformedURLException e) {
			throw new RuntimeException("Error creating password reset link", e); // Unexpected
		}
	}

	
	public CompletableFuture<Void> sendPasswordResetEmail(String emailAddress, String linkBaseUrl) {
		JoatseUser user = userRepository.findByLogin(emailAddress);
		if (user == null) {
			throw new NoSuchElementException("Couldn't find a user with email " + emailAddress);
		}
		String link = this.newPasswordResetLink(user, linkBaseUrl);
		SimpleMailMessage message = new SimpleMailMessage();
		message.setSubject("Joatse Cloud password reset");
		message.setText(
				"<h1>Joatse Cloud</h1><p>Someone requested a password reset for the Joatse Cloud account " + emailAddress + ". If it wasn't you then you can safely ignore this message. If it was really you then please follow this link to continue:<br /><a href=\""
						+ link + "\">" + link + "</a></p>");
		message.setTo(emailAddress);
		return asyncEmailSender.sendHtml(message);
	}

	public Optional<JoatseUser> getUserFromChangePasswordToken(UUID tokenUUID) {
		PasswordResetToken savedToken = passwordResetTokenRepository.findById(tokenUUID).orElse(null);
		if (savedToken == null) {
			return Optional.empty();
		} else if (!savedToken.getCreationTime().plus(1, ChronoUnit.DAYS).isAfter(Instant.now())) {
			return Optional.empty();
		} else {
			return Optional.ofNullable(savedToken.getUser());
		}
	}


	public void changePasswordWithToken(UUID tokenUUID, String password) {
		logout();
		PasswordResetToken savedToken = passwordResetTokenRepository.findById(tokenUUID).orElse(null);
		if (savedToken == null) {
			throw new NoSuchElementException("Can't find token: " + tokenUUID);
		}
		passwordResetTokenRepository.delete(savedToken);
		if (!savedToken.getCreationTime().plus(1, ChronoUnit.DAYS).isAfter(Instant.now())) {
			throw new IllegalArgumentException("Token is too old: " + tokenUUID);
		}
		JoatseUser user = savedToken.getUser();
		user.setPassword(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password));
		userRepository.save(user);
	}

	public boolean verifyUserEmailWithToken(UUID token) {
		JoatseUser user = getAuthenticatedUser().get();
		EmailVerificationToken savedToken = emailVerificationTokenRepository.findByUser(user).orElse(null);
		log.debug("Loaded emailVerificationToken {} for user {}", Optional.ofNullable(savedToken).map(t->t.getUuid()).orElse(null), user);
		if (savedToken == null) {
			log.warn("Token not found {} for user {}", token, user.getUsername());
			return false;
		} else if (!savedToken.getCreationTime().plus(1, ChronoUnit.DAYS).isAfter(Instant.now())) {
			log.warn("emailVerificationToken too old {} for user {}", token, user.getUsername());
			emailVerificationTokenRepository.delete(savedToken); // Old
			return false;
		} else if (savedToken.getUuid().equals(token)) {
			log.info("Email verification complete for user {}!!!!", user.getUsername());
			emailVerificationTokenRepository.delete(savedToken); // Correct
			/* We set the confirmation in the user in session and in the user in db separately, not assumming they are equal */
			user.setEmailConfirmed();
			userRepository.findById(user.getUuid()).ifPresent(u->{
				u.setEmailConfirmed();
				userRepository.save(u);
			});
			updateAuthenticatedUser();
			return true;
		} else {
			log.warn("emailVerificationToken mismatch {} != {} for user {}", token, savedToken.getUuid(), user.getUsername());
			// Don't delete. It might still be useful and correct if they use it.
			return false;
		}
	}
	
	/** Allow authenticated user to use the application */
	public void allowApplicationUse() {
		JoatseUser user = getAuthenticatedUser().get();
		user.allowApplicationUse();
		userRepository.findById(user.getUuid()).ifPresent(u->{
			u.allowApplicationUse();
			userRepository.save(u);
		});
		updateAuthenticatedUser();
	}

	public void preventApplicationUse() {
		JoatseUser user = getAuthenticatedUser().get();
		user.preventApplicationUse();
		userRepository.findById(user.getUuid()).ifPresent(u->{
			u.preventApplicationUse();
			userRepository.save(u);
		});
		updateAuthenticatedUser();
	}

	public void logout() {
		SecurityContextHolder.getContext().setAuthentication(null);
	}

	public void createUser(JoatseUser user) {
		userDetailsService().createUser(user);
	}

	public Iterable<JoatseUser> allUsers() {
		return userRepository.findAll();
	}

	public void updateUser(JoatseUser user) {
		userDetailsService().updateUser(user);
	}

	public JoatseUser loadUserByUUID(UUID uuid) {
		return userDetailsService().loadUserByUUID(uuid);
	}

	public void deleteUser(JoatseUser user) {
		userDetailsService().deleteUser(user);
	}

	public boolean isEmailEnabled() {
		return asyncEmailSender.isEnabled();
	}

	public String generateJwt(JoatseUser user) {
		List<String> authorities = user.getAuthorities().stream().map(a->a.getAuthority()).collect(Collectors.toList());		
		return joatseJwtTokenManager.generateToken(user.asAttributeMap(), authorities);
	}
}

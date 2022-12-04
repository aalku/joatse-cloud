package org.aalku.joatse.cloud.service.user;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.aalku.joatse.cloud.service.user.repository.OAuth2IssSubAccountRepository;
import org.aalku.joatse.cloud.service.user.repository.UserRepository;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.service.user.vo.OAuth2IssSubAccount;
import org.aalku.joatse.cloud.service.user.vo.OAuth2UserWrapper;
import org.aalku.joatse.cloud.service.user.vo.OidcUserWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UserManager {

	public static final GrantedAuthority AUTHORITY_NOT_USER = new SimpleGrantedAuthority("NOT_USER");

	@Autowired
	private UserRepository userRepository;
	
	@Autowired
	private OAuth2IssSubAccountRepository oauth2IssSubAccountRepository;
	
	@Value("${account.autoregister.admin.emails:}")
	private List<String> adminMailAccounts;
	
	@Value("${account.autoregister.user.emails:}")
	private List<String> userMailAccounts;

	private Logger log = LoggerFactory.getLogger(UserManager.class);

	@SuppressWarnings("unchecked")
	public Optional<JoatseUser> getAuthenticatedUser() {
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		if (principal instanceof JoatseUser) {
			return Optional.of((JoatseUser) principal);
		} else if (principal instanceof Supplier<?>) {
			JoatseUser joatseUser = ((Supplier<JoatseUser>)principal).get();
			if (joatseUser != null) {
				return Optional.of(joatseUser);
			}
		}
		return Optional.empty();
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
		if (iss != null && sub != null) {
			OAuth2IssSubAccount account = oauth2IssSubAccountRepository.findByIssAndSub(iss, sub);
			if (account != null) {
				log.info("Account found: {}", account.getUser());
				return account.getUser();
			} else {
				String email = Optional.ofNullable(oauth2User.getAttribute("email")).map(a -> (String) a)
						.filter(m -> !m.trim().isEmpty()).orElse(null);
				if (email != null && adminMailAccounts.contains(email)) {
					JoatseUser user = userRepository.findByLogin("admin");
					account = OAuth2IssSubAccount.create(user, iss, sub);
					oauth2IssSubAccountRepository.save(account);
					log.info("Admin account linked: {} = {}", user, email);
					return user;
				} else if (email != null && userMailAccounts.contains(email)) {
					JoatseUser user = userRepository.findByLogin(email);
					if (user != null) {
						log.warn("email prerregistered as user but it exists as account: {}", email);
						return null; 
					}
					user = JoatseUser.newLocalUser(email);
					account = OAuth2IssSubAccount.create(user, iss, sub);					
					userRepository.save(user);
					oauth2IssSubAccountRepository.save(account); // Saves user too
					return user;
				}
			}
		}
		return null;
	}

	@Bean
	public JoatseUserDetailsManager userDetailsService() {
		JoatseUser admin = userRepository.findByLogin("admin");
		if (admin == null || admin.getPassword() == null || admin.getPassword().trim().isEmpty()) {
			admin = JoatseUser.newLocalUser("admin");
			admin.setPassword(randomPassword(pw->saveTempAdminPassword(pw)));
			admin.addAuthority(new SimpleGrantedAuthority("ROLE_JOATSE_ADMIN"));
			userRepository.save(admin);
		}
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

	private void saveTempAdminPassword(CharSequence pw) {
		Path path = Path.of("joatse_admin_temp_password.txt");
		String instructions1 = "This file contains the original password for admin user.";
		String instructions2 = "This file does not update if the password is changed and any manual edit of this file will have no effect.";
		String instructions3 = "If you lost the password you can reset it in the database file.";
		String instructions4 = "If you set the field blank a new one will be generated and this file will be overwritten in the next restart.";
		try {
			Files.write(path, Arrays.asList(instructions1, instructions2, instructions3, instructions4, "", pw), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			System.err.println("Admin password save to file: " + path.toAbsolutePath());
		} catch (IOException e) {
			throw new RuntimeException("Can't write password file: " + e.toString());
		}
	}

	private String randomPassword(Consumer<CharSequence> passwordListener) {
		/* Generate, print, encode, forget */
	    int passLen = 30;
	    String AB = "023456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
	    Random rnd = new Random();
		StringBuilder sb = new StringBuilder(passLen);
	    for (int i = 0; i < passLen; i++) {
	        sb.append(AB.charAt(rnd.nextInt(AB.length())));
	    }
	    passwordListener.accept(sb);
		PasswordEncoder ec = PasswordEncoderFactories.createDelegatingPasswordEncoder();
	    String encoded = ec.encode(sb);
	    sb.setLength(0);
	    for (int i = 0; i < sb.capacity(); i++) {
	    	sb.append(' ');
	    }
		return encoded;
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

}

package org.aalku.joatse.cloud.web;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.aalku.joatse.cloud.config.WebSecurityConfiguration;
import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.tools.io.AsyncEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class JoatseLoginController implements InitializingBean {
	
	private Logger log = LoggerFactory.getLogger(JoatseLoginController.class);
	
	@Autowired(required = false)
	private ClientRegistrationRepository clientRegistrationRepository;
	
	@Autowired
	private UserManager userManager;
	
	@Value("${loginPasswordEnabled:true}")
	private String loginPasswordEnabled; // TODO call a service
	
	private String oauthLoginBaseUrl = OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI;
	
	private Map<String, String> oauth2Registrations = null;
	
	@Autowired
	private AsyncEmailSender asyncEmailSender;
	
	@Value("${cloud.email.verification.enabled:true}")
	private boolean emailVerificationEnabled;
	
	/**
	 * Entry point after login to redirect depending on session attributes.
	 * 
	 * We might be using a confirmation link (logged in or not) or we might need
	 * email verification or otherwise just let the user use the app.
	 * 
	 */
	@GetMapping(WebSecurityConfiguration.PATH_POST_LOGIN)
	public String postLogin(jakarta.servlet.http.HttpSession session) {
		if (session.getAttribute(ConfirmController.CONFIRM_SESSION_KEY_HASH) != null) {
			/* We are confirming a request */
			return "forward:" + ConfirmController.POST_LOGIN_CONFIRM_HASH;
		} else {
			Optional<JoatseUser> user = userManager.getAuthenticatedUser();
			log.warn("postLogin - User: {}", user);
			if (user.map(u->u.isEmailConfirmationNeeded()).orElse(false)) {
				if (emailVerificationEnabled) {
					/*
					 * If verification is enabled and user is not verified then don't allow they to
					 * use the app and redirect to email verification
					 */
					userManager.preventApplicationUse();
					return "redirect:/postLogin/emailVerification";
				} else {
					/*
					 * If verification is disabled then don't consider it permanently verified but
					 * allow to use the app and redirect to /
					 */
					userManager.allowApplicationUse();
					return "redirect:/";
				}
			}
			return "redirect:/";
		}
	}
	
	@GetMapping(WebSecurityConfiguration.PATH_POST_LOGIN + "/emailVerification")
	public String emailVerification(jakarta.servlet.http.HttpServletRequest request, @RequestParam(required = false) UUID token) throws Exception {
		JoatseUser user = userManager.getAuthenticatedUser().get();
		if (!user.isEmailConfirmationNeeded()) {
			return "redirect:/";
		}
		String to = Optional.of(user).map(u -> u.getUsername()).filter(u -> u.matches("[^@\\s]+@[^@\\s]+[.][^@\\s]+"))
				.orElse(null);
		if (to == null) {
			throw new IllegalStateException("User login is not an email: " + user);
		}
		if (token != null) {
			if (userManager.verifyUserEmailWithToken(token)) {
				return "redirect:/";
			} else {
				return "forward:/userManagement/emailVer-failed.html";
			}
		} else {
			try {
				SimpleMailMessage message = new SimpleMailMessage();
				token = userManager.newEmailVerificationToken();
				String link = new URL(new URL(request.getRequestURL().toString()), "/postLogin/emailVerification?token=" + token).toExternalForm();
				message.setSubject("Joatse Cloud email address verification");
				message.setText(
						"<h1>Joatse Cloud</h1><p>Please confirm this is your email address and you are creating a Joatse Cloud account by visiting this link:<br /><a href=\""
								+ link + "\">" + link + "</a></p>");
				message.setTo(to);
				asyncEmailSender.sendHtml(message).get();
			} catch (InterruptedException | ExecutionException e) {
				throw e;
			}
			return "forward:/userManagement/emailVer-w8-4it.html";
		}
	}
	
	/**
	 * Prompt for details to reset password.
	 */
	@GetMapping(WebSecurityConfiguration.PATH_PASSWORD_RESET)
	public String passwordResetPage(jakarta.servlet.http.HttpServletRequest request) throws Exception {
		return "forward:/resetPassword/resetPassword.html";
	}

	/**
	 * Password reset steps.
	 */
	@PostMapping(WebSecurityConfiguration.PATH_PASSWORD_RESET)
	@ResponseBody
	public Map<String, Object> passwordResetAction(jakarta.servlet.http.HttpServletRequest request,
			@RequestBody Map<String, Object> payload) throws Exception {
		log.debug("passwordResetAction - Payload: {}", payload);
		Map<String, Object> res = new LinkedHashMap<>();
		Optional<String> email = Optional.ofNullable(payload.get("email")).map(v -> v.toString());
		Optional<UUID> token = Optional.ofNullable(payload.get("token")).map(v -> v.toString()).map(u->UUID.fromString(u));
		Optional<String> password = Optional.ofNullable(payload.get("password")).map(v -> v.toString());
		if (email.isPresent() && !token.isPresent() && !password.isPresent()) {
			if (asyncEmailSender.isEnabled()) {
				String emailAddress = email.get();
				try {
					userManager.sendPasswordResetEmail(emailAddress, request.getRequestURL().toString()).get();
					res.put("result", "success");
					res.put("msg", "reset email sent");
				} catch (NoSuchElementException e) {
					res.put("result", "error");
					res.put("msg", "The entered email does not identify an user");
				} catch (Exception e) {
					res.put("result", "error");
					res.put("msg", "There was an error sending the reset email");
				}
			} else {
				res.put("result", "error");
				res.put("msg", "The email system is not available. The system administrator should send you a password reset link as soon as possible.");
			}
		} else if (token.isPresent()) {
			if (password.isPresent()) {
				userManager.changePasswordWithToken(token.get(), password.get());
				res.put("result", "success");
				res.put("msg", "Password reset success");
			} else {
				Optional<JoatseUser> user = userManager.getUserFromChangePasswordToken(token.get());
				if (user.isPresent()) {
					res.put("result", "success");
					res.put("username", user.get().getUsername());
				} else {
					res.put("result", "error");
					res.put("msg", "Invalid token");
				}
			}
		} else {
			throw new IllegalArgumentException("Payload is not as expected");
		}
		log.debug("passwordResetAction - response: {}", res);
		return res;
	}

	@GetMapping(path = "/loginForm/options", produces = "application/json")
	@ResponseBody
	public Map<String, Object> getOptions() {
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("loginPasswordEnabled", loginPasswordEnabled);
		res.put("oauth2Registrations", oauth2Registrations);
		return res;
	}

	@GetMapping("/loginForm")
	public String login() {
		return "forward:login.html";
	}
	
	@Override
	public void afterPropertiesSet() throws Exception {
		setupOAuth2Registrations();
		setupEmailVerification();
	}

	private void setupOAuth2Registrations() {
		oauth2Registrations = new LinkedHashMap<>();
		if (clientRegistrationRepository instanceof Iterable) {
			@SuppressWarnings("unchecked")
			Iterable<ClientRegistration> it = (Iterable<ClientRegistration>)clientRegistrationRepository;
			for (ClientRegistration x: it) {
				if (x.getAuthorizationGrantType().getValue().equals("authorization_code")) {
					oauth2Registrations.put(x.getClientName(), oauthLoginBaseUrl + "/" + x.getRegistrationId());
				}
			}
		}
		// System.err.println(oauth2Registrations);
	}
	
	private void setupEmailVerification() {
		if (emailVerificationEnabled && !asyncEmailSender.isEnabled()) {
			throw new IllegalStateException("In order to enable email verification the email sending must be configured");
		}
	}
}

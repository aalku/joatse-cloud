package org.aalku.joatse.cloud.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class JoatseLoginController implements InitializingBean {
	
	
	@Autowired(required = false)
	private ClientRegistrationRepository clientRegistrationRepository;
	
	@Autowired
	private UserManager userManager;
	
	@Value("${loginPasswordEnabled:true}")
	private String loginPasswordEnabled; // TODO call a service
	
	private String oauthLoginBaseUrl = OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI;
	
	private Map<String, String> oauth2Registrations = null;
	
	/**
	 * Entry point after login to redirect depending on session attributes 
	 */
	@GetMapping("/postLogin")
	public View postLogin(jakarta.servlet.http.HttpSession session) {
		if (session.getAttribute(ConfirmController.CONFIRM_SESSION_KEY_HASH) != null) {
			/* We are confirming a request */
			return new RedirectView(ConfirmController.POST_LOGIN_CONFIRM_HASH);
		} else {
			return new RedirectView("/");
		}
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
	
	@GetMapping("/user")
	@ResponseBody
	public Map<String, Object> getUser() {
		JoatseUser user = userManager.getAuthenticatedUser().orElseThrow();
		return Map.of(
				"nameToAddress", user.getUsername(),
				"isAdmin", user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_JOATSE_ADMIN"))
			);
	}

}

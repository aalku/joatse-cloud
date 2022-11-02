package org.aalku.joatse.cloud.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.aalku.joatse.cloud.service.user.JoatseUser;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class JoatseLoginController implements InitializingBean {
	
	
	/* TODO Model: org.springframework.security.web.authentication.ui.DefaultLoginPageGeneratingFilter */
	
	@Autowired(required = false)
	private ClientRegistrationRepository clientRegistrationRepository;
	
	@Value("${loginPasswordEnabled:true}")
	private String loginPasswordEnabled; // TODO call a service
	
	private String oauthLoginBaseUrl = OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI;
	
	private Map<String, String> oauth2Registrations = null;
	
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
		JoatseUser user = (JoatseUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		return Map.of(
				"nameToAddress", user.getNameToAddress()
				);
	}

}

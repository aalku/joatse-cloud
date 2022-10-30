package org.aalku.joatse.cloud.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.WebAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;

@Controller
public class JoatseLoginController implements InitializingBean {
	
	
	/* TODO Model: org.springframework.security.web.authentication.ui.DefaultLoginPageGeneratingFilter */
	
	@Autowired(required = false)
	ClientRegistrationRepository clientRegistrationRepository;
	
	@Value("${loginPasswordEnabled:true}")
	private String loginPasswordEnabled; // TODO call a service
	
	private String oauthLoginBaseUrl = OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI;
	
	private Map<String, String> oauth2Registrations = null;
	
	@GetMapping("/loginForm")
	public String login(
			HttpServletRequest request, 
			Model model) {
		Map<String, String[]> paramMap = request.getParameterMap();
	    boolean logout = paramMap.containsKey("logout");
	    String errorMessage = paramMap.containsKey("error") ? getErrorMessage(request) : null;
	    
		model.addAttribute("contextPath", request.getContextPath());
		model.addAttribute("loginPasswordEnabled", loginPasswordEnabled);
		model.addAttribute("oauth2Registrations", oauth2Registrations);
		model.addAttribute("loggedOut", logout);
		model.addAttribute("errorMessage", errorMessage);

		return "login.html";
	}
	
	private String getErrorMessage(HttpServletRequest request) {
		return Optional.ofNullable(request.getSession(false))
				.map(s -> (AuthenticationException) s.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION))
				.map(e -> e.getMessage()).orElse("Invalid user or password");
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
		System.err.println(oauth2Registrations);
	}
}

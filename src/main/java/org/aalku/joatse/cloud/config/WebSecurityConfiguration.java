package org.aalku.joatse.cloud.config;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;
import java.util.Random;

import org.aalku.joatse.cloud.service.user.JoatseUser;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class WebSecurityConfiguration {

	public static final String PATH_LOGIN_FORM = "/loginForm";
	public static final String PATH_LOGIN_POST = "/loginPost";
	
	@SuppressWarnings("unused")
	private Logger log = LoggerFactory.getLogger(WebSecurityConfiguration.class);
	
	@Bean
	public ApplicationListener<WebServerInitializedEvent> webInitializedListener() {
		return new ApplicationListener<WebServerInitializedEvent>() {
			
			@Override
			public void onApplicationEvent(WebServerInitializedEvent event) {
				event.hashCode();
			}
		};
	}

	@Autowired(required = false)
	private ClientRegistrationRepository clientRegistrationRepository;

	/**
	 */
	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		
		// UserService for OAuth2 login
		Customizer<OAuth2LoginConfigurer<HttpSecurity>> oauth2LoginCustomizer = new Customizer<OAuth2LoginConfigurer<HttpSecurity>>() {
			@Override
			public void customize(OAuth2LoginConfigurer<HttpSecurity> x) {
				if (clientRegistrationRepository != null) {
					x.userInfoEndpoint(new Customizer<OAuth2LoginConfigurer<HttpSecurity>.UserInfoEndpointConfig>() {
						@Override
						public void customize(OAuth2LoginConfigurer<HttpSecurity>.UserInfoEndpointConfig xx) {
							DefaultOAuth2UserService userService = new DefaultOAuth2UserService() {
								@Override
								public OidcUser loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
									return updateUser(userRequest, super.loadUser(userRequest));
								}
							};
							xx.userService(userService);
							xx.oidcUserService(new OAuth2UserService<OidcUserRequest, OidcUser>() {
								@Override
								public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
									return (OidcUser) userService.loadUser(userRequest);
								}
							});
						}
					});
					x.loginPage(PATH_LOGIN_FORM);
					x.successHandler(new AuthenticationSuccessHandler() {
						@Override
						public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
								Authentication authentication) throws IOException, ServletException {
							// TODO registerAuthSuccess(...);
							response.sendRedirect("/postLogin");
						}
					}).failureHandler(new AuthenticationFailureHandler() {
						@Override
						public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
								AuthenticationException exception) throws IOException, ServletException {
							// TODO registerAuthFailure(...);
							logout(request);
							response.sendRedirect("/");
						}
					});
				} else {
					x.disable();
				}
			}
		};
		http.csrf().disable()
			.authorizeRequests()
				.antMatchers(WebSocketConfig.CONNECTION_HTTP_PATH).anonymous()
				.antMatchers(PATH_LOGIN_FORM, PATH_LOGIN_FORM + "/**", PATH_LOGIN_POST).permitAll()
				.antMatchers("/CF", "/CF/2").permitAll()
				.anyRequest().fullyAuthenticated()
        	.and()
        	.formLogin(c->{
        		c.loginPage(PATH_LOGIN_FORM).loginProcessingUrl(PATH_LOGIN_POST).successHandler(new AuthenticationSuccessHandler() {
					@Override
					public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
							Authentication authentication) throws IOException, ServletException {
						// TODO registerAuthSuccess(...);
						response.setStatus(200);
						JSONObject js = new JSONObject();
						js.put("success", true);
						PrintWriter writer = response.getWriter();
						writer.println(js.toString());
						writer.flush();
					}
				}).failureHandler(new AuthenticationFailureHandler() {
					@Override
					public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
							AuthenticationException exception) throws IOException, ServletException {
						// TODO registerAuthFailure(...);
						logout(request);
						response.setStatus(401);
						JSONObject js = new JSONObject();
						js.put("success", false);
						js.put("message", "Invalid user or password");
						PrintWriter writer = response.getWriter();
						writer.println(js.toString());
						writer.flush();
					}
				});
        	})
        	.oauth2Login(oauth2LoginCustomizer);
		return http.build();
	}

	private void logout(HttpServletRequest request) throws ServletException {
		request.logout();
		Optional.ofNullable(request.getSession(false)).ifPresent(s->s.invalidate());
	}

	private JoatseUser updateUser(OAuth2UserRequest userRequest, OAuth2User loadedUser) {
		// TODO save, load, integrate
		
	    JoatseUser user = new JoatseUser(loadedUser, userRequest.getClientRegistration().getRegistrationId());
	    return user;
	}

	@Bean
	public UserDetailsManager userDetailsService() {
		String userName = "admin";
		UserDetails user = User.builder()
				.username(userName)
				.password(randomPassword(userName))
				.roles("ADMIN")
				.build();
		return new InMemoryUserDetailsManager(user) {
			@Override
			public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
				UserDetails user = super.loadUserByUsername(username);
				return new JoatseUser(user);
			}
		};
	}

	private String randomPassword(String userName) {
		/* Generate, print, encode, forget */
	    int passLen = 30;
	    String AB = "023456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
	    Random rnd = new Random();
		StringBuilder sb = new StringBuilder(passLen);
	    for (int i = 0; i < passLen; i++) {
	        sb.append(AB.charAt(rnd.nextInt(AB.length())));
	    }
		System.err.println("Random admin credentials are: " + userName + "/" + sb);
		PasswordEncoder ec = PasswordEncoderFactories.createDelegatingPasswordEncoder();
	    String encoded = ec.encode(sb);
	    sb.setLength(0);
	    for (int i = 0; i < sb.capacity(); i++) {
	    	sb.append(' ');
	    }
		return encoded;
	}
}

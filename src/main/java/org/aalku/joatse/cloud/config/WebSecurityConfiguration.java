package org.aalku.joatse.cloud.config;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;

import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.service.user.vo.OidcUserWrapper;
import org.aalku.joatse.cloud.web.jwt.JWTAuthorizationFilter;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableMethodSecurity(securedEnabled = true)
public class WebSecurityConfiguration {

	public static final String PATH_LOGIN_FORM = "/loginForm";
	public static final String PATH_LOGIN_POST = "/loginPost";
	public static final String PATH_POST_LOGIN = "/postLogin";
	public static final String PATH_PASSWORD_RESET = "/resetPassword";
	public static final String PATH_PUBLIC = "/public/**";
	
	@SuppressWarnings("unused")
	private Logger log = LoggerFactory.getLogger(WebSecurityConfiguration.class);
	
	@Bean // Must be public
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
	
	@Autowired
	private UserManager userManager;
	
	/**
	 */
	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, JWTAuthorizationFilter jwtAuthorizationFilter) throws Exception {
		http.headers(headers -> {
			headers.httpStrictTransportSecurity(sts -> {
				// Subdomains should be able to have different settings
				sts.includeSubDomains(false).maxAgeInSeconds(60 * 60 * 24);
			});
		});

		http.csrf(csrf -> csrf.disable())
                .addFilterBefore(jwtAuthorizationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(t -> t.requestMatchers(WebSocketConfig.JOATSE_CONNECTION_HTTP_PATH).anonymous()
				//
                .requestMatchers(HttpMethod.GET, PATH_PUBLIC).permitAll()
                .requestMatchers(HttpMethod.GET, "/login.html", "/css/**", "/header.js", "/lib/*.js").permitAll()
				.requestMatchers(HttpMethod.GET, "/user").permitAll()
				.requestMatchers("/error").permitAll()
				//
				.requestMatchers(PATH_LOGIN_FORM, PATH_LOGIN_FORM + "/**", PATH_LOGIN_POST, PATH_LOGIN_POST + "/**").permitAll()
				.requestMatchers(PATH_PASSWORD_RESET, PATH_PASSWORD_RESET + "/**").permitAll()
				.requestMatchers(PATH_POST_LOGIN, PATH_POST_LOGIN + "/**").authenticated() // But not any specific auth
				.requestMatchers("/CF", "/CF/2").permitAll()
				.requestMatchers("/").authenticated() // But not any specific auth
				.anyRequest().hasRole("JOATSE_USER"))
                .formLogin(c -> {
                    c.loginPage(PATH_LOGIN_FORM).loginProcessingUrl(PATH_LOGIN_POST).successHandler(new AuthenticationSuccessHandler() {
                        @Override
                        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                                                        Authentication authentication) throws IOException, ServletException {
                            // TODO registerAuthSuccess(...);
                            setLoginCookie(response, (JoatseUser) authentication.getPrincipal());
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
                .oauth2Login(new Customizer<OAuth2LoginConfigurer<HttpSecurity>>() {
                    @Override
                    public void customize(OAuth2LoginConfigurer<HttpSecurity> x) {
                        if (clientRegistrationRepository != null) {
                            x.userInfoEndpoint(new Customizer<OAuth2LoginConfigurer<HttpSecurity>.UserInfoEndpointConfig>() {
                                @Override
                                public void customize(OAuth2LoginConfigurer<HttpSecurity>.UserInfoEndpointConfig xx) {
                                    xx.userService(userManager.getOAuth2UserService());
                                    xx.oidcUserService(userManager.getOidcUserService());
                                }
                            });
                            x.loginPage(PATH_LOGIN_FORM);
                            x.successHandler(new AuthenticationSuccessHandler() {
                                @Override
                                public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                                                                        Authentication authentication) throws IOException, ServletException {
                                    // TODO registerAuthSuccess(...);
                                    JoatseUser user = ((OidcUserWrapper) authentication.getPrincipal()).get();
                                    setLoginCookie(response, user);
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
                }).logout(logout -> {
            logout.deleteCookies(JWTAuthorizationFilter.COOKIE_JWT_KEY);
        }).sessionManagement(management -> management.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		return http.build();
	}

	private void setLoginCookie(HttpServletResponse response, JoatseUser user) {
		String jwt = userManager.generateJwt(user);
		Cookie cookie = new Cookie(JWTAuthorizationFilter.COOKIE_JWT_KEY, jwt);
		cookie.setPath("/");
		response.addCookie(cookie);
	}

	private void logout(HttpServletRequest request) throws ServletException {
		request.logout();
		Optional.ofNullable(request.getSession(false)).ifPresent(s->s.invalidate());
	}

}

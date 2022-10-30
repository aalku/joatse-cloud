package org.aalku.joatse.cloud.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WebListenerConfigurationDetector implements InitializingBean {
	private Logger log = LoggerFactory.getLogger(WebListenerConfigurationDetector.class);

	@Value("${security.require-ssl:}")
	private Boolean sslRequired;
	@Value("${server.ssl.enabled:}")
	private Boolean sslEnabled;
	@Value("${server.port}")
	private Integer serverPort;
	@Value("${server.hostname.public:}")
	private String publicHostname;

	private boolean autoHostname;
	
	@Override
	public void afterPropertiesSet() throws Exception {
		this.sslEnabled = (sslEnabled != null && sslEnabled);
		this.sslRequired = (sslRequired != null && sslRequired) && sslEnabled;
		this.autoHostname = publicHostname == null || publicHostname.isBlank();
		if (autoHostname) {
			publicHostname = System.getenv("HOSTNAME");
		}
		if (publicHostname == null || publicHostname.isBlank()) {
			publicHostname = System.getenv("HOST");
		}
		if (publicHostname == null || publicHostname.isBlank()) {
			publicHostname = System.getenv("COMPUTERNAME");
		}
		if (publicHostname == null || publicHostname.isBlank()) {
			throw new Exception(
					"You must configure 'server.hostname.public' as the public hostname to tell the users to connect to.");
		}
	}
	
	@EventListener(ApplicationReadyEvent.class)
	public void doSomethingAfterStartup() {
		if (autoHostname) {
			log.warn(
					"The 'server.hostname.public' is not defined. This system will use the autodetected name '{}' but you probably should configure it.",
					publicHostname);
		}
		if (!sslEnabled) {
			log.warn("You should configure SSL: server.ssl.enabled=true");
		}
		if (!sslEnabled) {
			log.warn("You should require SSL: security.require-ssl=true");
		}
	}
	
	public boolean isSslEnabled() {
		return sslEnabled;
	}
	public boolean getSslRequired() {
		return sslRequired;
	}

	public String getPublicHostname() {
		return publicHostname;
	}

	public Integer getServerPort() {
		return serverPort;
	}

}
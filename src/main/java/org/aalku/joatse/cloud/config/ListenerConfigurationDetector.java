package org.aalku.joatse.cloud.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ListenerConfigurationDetector implements InitializingBean {
	private Logger log = LoggerFactory.getLogger(ListenerConfigurationDetector.class);

	@Value("${security.require-ssl:}")
	private Boolean sslRequired;
	@Value("${server.ssl.enabled:}")
	private Boolean sslEnabled;
	@Value("${server.port}")
	private Integer serverPort;
	@Value("${server.hostname.public:}")
	private String publicHostname;
	@Value("${cloud.tcp.tunnel.host:}")
	private String publicHostnameTcp;

	/**
	 * publicHostname was get automatically because no manual publicHostname selected
	 */
	private boolean autoHostname;

	private boolean autoHostnameTcp;
	
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
		if (publicHostnameTcp == null || publicHostnameTcp.isBlank()) {
			publicHostnameTcp = publicHostname; // We don't know if we could use a subdomain
			autoHostnameTcp = true;
		}
	}
	
	@EventListener(ApplicationReadyEvent.class)
	public void doSomethingAfterStartup() {
		if (autoHostname) {
			log.warn(
					"The 'server.hostname.public' is not defined. This system will use the autodetected name '{}' but you probably should configure it.",
					publicHostname);
		}
		if (autoHostnameTcp) {
			log.warn(
					"The 'cloud.tcp.tunnel.host' is not defined. This system will use the autodetected name '{}' but you probably should configure it. The best choice would be a subdomain of '{}'",
					publicHostnameTcp, publicHostname);
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

	public Object getPublicHostnameTcp() {
		return publicHostnameTcp;
	}

	public Integer getServerPort() {
		return serverPort;
	}

}
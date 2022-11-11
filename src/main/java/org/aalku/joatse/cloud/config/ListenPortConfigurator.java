package org.aalku.joatse.cloud.config;

import java.net.InetAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.aalku.joatse.cloud.tools.io.AsyncTcpPortListener;
import org.aalku.joatse.cloud.tools.io.PortRange;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configure port ranges in order checking for collisions
 */
@Configuration
public class ListenPortConfigurator implements InitializingBean {

	@Value("${cloud.port.open.range:}")
	private String openPortRangeString;

	@Value("${cloud.http.port.range:}")
	private String httpOpenPortRangeString;
	
	@Value("${cloud.http.tunnel.hosts:}")
	private Set<String> httpHosts;

	@Autowired
	private ListenerConfigurationDetector webListenerConfiguration;

	@Bean("openPortRange")
	public PortRange openPortRange() throws Exception {
		PortRange openPortRange = new PortRange();
		openPortRange.setup(openPortRangeString, "cloud.port.open.range",
				Collections.singletonMap(webListenerConfiguration.getServerPort(), "server.port"),
				Collections.emptyMap());
		return openPortRange;
	}
	
	@Bean("httpPortRange")
	public PortRange httpPortRange(@Autowired @Qualifier("openPortRange") PortRange openPortRange) throws Exception {

		Map<PortRange, String> forbidenRanges = new LinkedHashMap<>();
		forbidenRanges.put(openPortRange, "cloud.port.open.range");

		PortRange httpPortRange = new PortRange();
		httpPortRange.setup(httpOpenPortRangeString, "cloud.http.port.range",
				Collections.singletonMap(webListenerConfiguration.getServerPort(), "server.port"), forbidenRanges);
		return httpPortRange;
	}
	
	@Bean("switchboardPortListener")
	public AsyncTcpPortListener<Void> switchboardPortListener() {
		return new AsyncTcpPortListener<Void>(InetAddress.getLoopbackAddress(), 0);
	}

	@Bean("httpHosts")
	public Set<String> httpHosts() {
		return httpHosts;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		if (httpHosts.isEmpty()) {
			httpHosts.add(webListenerConfiguration.getPublicHostname());
		}
	}

}

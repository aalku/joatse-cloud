package org.aalku.joatse.cloud.service.sharing.http;

import java.util.LinkedHashSet;
import java.util.Set;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.tools.io.PortRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpEndpointGenerator {
	
	@Autowired
	@Qualifier("httpPortRange")
	private PortRange httpPortRange;
	
	@Autowired
	@Qualifier("httpUnsafePortRange")
	private PortRange httpUnsafePortRange;

	@Autowired
	@Qualifier("httpHosts")
	private Set<String> httpHosts;
	
	@Autowired
	private ListenerConfigurationDetector listenerConfigurationDetector;

	public ListenAddress generateListenAddress(HttpTunnel tunnel, LinkedHashSet<ListenAddress> forbiddenAddresses) {
		// TODO dynamic hostnames if enabled
		PortRange portRange = tunnel.isUnsafe() ? httpUnsafePortRange : httpPortRange;
		String protocol = listenerConfigurationDetector.isSslEnabled() ? "https" : "http";
		for (int p = portRange.min(); p < portRange.max(); p++) {
			for (String host : httpHosts) {
				ListenAddress a = new ListenAddress(p, host, protocol);
				if (!forbiddenAddresses.contains(a)) {
					return a;
				}
			}
		}
		throw new RuntimeException("Not enough http(s) ports or host names (" + portRange.count() + "x" + httpHosts.size() + ") for this share size" );
	}

}

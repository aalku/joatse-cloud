package org.aalku.joatse.cloud.service.sharing.http;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.tools.io.PortRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpEndpointGenerator {
	
	public enum PrefixStrategy {
		URL, DESCRIPTION
	}

	private static final String DYNAMIC_PREFIX = "*.";

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

	@Value("${httpEndpointGenerator.prefixStrategy:DESCRIPTION}")
	private PrefixStrategy prefixStrategy;

	@Value("${httpEndpointGenerator.prefixHashLength:6}")
	private int prefixHashLength;

	public ListenAddress generateListenAddress(HttpTunnel tunnel, LinkedHashSet<ListenAddress> forbiddenAddresses,
			String askedCloudHostname) {
		PortRange portRange = tunnel.isUnsafe() ? httpUnsafePortRange : httpPortRange;
		String protocol = listenerConfigurationDetector.isSslEnabled() ? "https" : "http";
		if (askedCloudHostname != null) {
			boolean possible = httpHosts.stream()
					.filter(x -> x.equals(askedCloudHostname)
							|| (x.startsWith(DYNAMIC_PREFIX) && askedCloudHostname.endsWith(x.substring(DYNAMIC_PREFIX.length()))))
					.findFirst().isPresent();
			if (!possible) {
				throw new RuntimeException("Asked hostname '" + askedCloudHostname
						+ "' is not allowed. Allowed hostnames are: " + httpHosts);
			} else {
				for (int p = portRange.min(); p < portRange.max(); p++) {
					for (String host : httpHosts) {
						ListenAddress a = new ListenAddress(p, host, protocol);
						if (!forbiddenAddresses.contains(a)) {
							return a;
						}
					}
				}		
				throw new RuntimeException("Asked hostname '" + askedCloudHostname + "' is not available");
			}
		} else {
			for (int p = portRange.min(); p < portRange.max(); p++) {
				for (String host : httpHosts) {
					ListenAddress a;
					if (host.startsWith(DYNAMIC_PREFIX)) {
						a = new ListenAddress(p, generatePrefix(tunnel) + "." + host.substring(DYNAMIC_PREFIX.length()), protocol);
					} else {
						a = new ListenAddress(p, host, protocol);
					}
					if (!forbiddenAddresses.contains(a)) {
						return a;
					}
				}
			}
			throw new RuntimeException("Not enough http(s) ports or host names (" + portRange.count() + "x"
					+ httpHosts.size() + ") for this share size");
		}
	}

	private String generatePrefix(HttpTunnel tunnel) {
		String prefixBase;
		if (this.prefixStrategy == PrefixStrategy.URL) {
			prefixBase = generateSummaryPreffixWithURL(tunnel);
		} else if (this.prefixStrategy == PrefixStrategy.DESCRIPTION) {
			prefixBase = generateSummaryPreffixWithDescription(tunnel);
		} else {
			throw new IllegalArgumentException();
		}
		return prefixBase + "-" + generateHashPrefix(tunnel, this.prefixHashLength);
	}
	
	private static String cleanString(String str) {
		return str.replaceAll("[^a-zA-Z0-9]+", "-").replaceFirst("^-", "").replaceFirst("-$", "").replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
	}

	private static String generateSummaryPreffixWithDescription(HttpTunnel tunnel) {
		StringBuilder sb = new StringBuilder(cleanString(tunnel.getTargetDescription()));
		return sb.toString();
	}

	private static String generateSummaryPreffixWithURL(HttpTunnel tunnel) {
		URL url = tunnel.getTargetURL();
		StringBuilder sb = new StringBuilder(cleanString(url.getHost()));
		Optional.of(url.getPort()).filter(p -> p >=0 && p != 80 && p != 443).ifPresent(p->{
			sb.append("-").append(p);
		});
		return sb.toString();
	}

	private static String generateHashPrefix(HttpTunnel tunnel, int hashLength) {
		int targetPort = tunnel.getTargetPort();
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			md.update(tunnel.getTunnel().getRequesterAddress().getAddress().getAddress());
			md.update(new byte[] {(byte)(targetPort & 0xff), (byte)((targetPort >> 8) & 0xff)});
			md.update(tunnel.getTargetURL().toExternalForm().getBytes(StandardCharsets.UTF_8));
			byte[] digest = md.digest();
			if (hashLength > digest.length) {
				throw new IllegalArgumentException("Selected hash algorithm can only generate " + digest.length + " chars");
			}
			StringBuilder sb = new StringBuilder(2);
			final String dictionary = "abcdefghmprstxyz"; 
			for (int i = 0; i < hashLength; i++) {
				char c = dictionary.charAt(digest[i] & 0x0F);
				sb.append(c);
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

}

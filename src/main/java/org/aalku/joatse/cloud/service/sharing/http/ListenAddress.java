package org.aalku.joatse.cloud.service.sharing.http;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;

public class ListenAddress {
	private final int listenPort;
	private final String cloudProtocol;
	private final String cloudHostname;
	
	public ListenAddress(int listenPort, String cloudHostname, String cloudProtocol) {
		this.listenPort = listenPort;
		this.cloudProtocol = cloudProtocol;
		this.cloudHostname = cloudHostname;
	}
	
	public URL getListenUrl(Optional<String> file) {
		try {
			// The multi-argument URI constructor encodes the path component automatically,
			// so we must NOT pre-encode the path. We only need to normalize slashes.
			String path = file
				.map(f -> {
					String normalized = f.startsWith("/") ? f : "/" + f;
					return normalized;
				})
				.orElse("");
			// The URI constructor will properly encode spaces, %, #, ?, etc. in the path
			return new URI(getCloudProtocol(), null, getCloudHostname(), getListenPort(), path, null, null).toURL();
		} catch (MalformedURLException | java.net.URISyntaxException e) {
			throw new RuntimeException(e.toString(), e);
		}
	}
	public int getListenPort() {
		return listenPort;
	}
	public String getCloudProtocol() {
		return cloudProtocol;
	}
	public String getCloudHostname() {
		return cloudHostname;
	}
	
	@Override
	public int hashCode() {
		return Objects.hash(cloudHostname, cloudProtocol, listenPort);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ListenAddress other = (ListenAddress) obj;
		return Objects.equals(cloudHostname, other.cloudHostname) && Objects.equals(cloudProtocol, other.cloudProtocol)
				&& listenPort == other.listenPort;
	}
}
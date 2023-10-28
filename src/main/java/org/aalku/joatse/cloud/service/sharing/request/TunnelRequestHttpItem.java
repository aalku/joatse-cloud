package org.aalku.joatse.cloud.service.sharing.request;

import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class TunnelRequestHttpItem extends TunnelRequestItem {

	private final URL targetUrl;
	private final boolean unsafe; // Allow unsafe https
	private final AtomicReference<String> listenHostname;
	
	public TunnelRequestHttpItem(long targetId, String targetDescription, URL targetUrl, boolean unsafe, Optional<String> listenHostname) {
		super(targetId, targetDescription, targetUrl.getHost(),
				Optional.of(targetUrl.getPort()).map(p -> p <= 0 ? targetUrl.getDefaultPort() : p).get());
		this.targetUrl = targetUrl;
		this.unsafe = unsafe;
		this.listenHostname = new AtomicReference<>(listenHostname.orElse(null));
	}
	
	@Override
	public boolean equals(TunnelRequestItem o) {
		if (o instanceof TunnelRequestHttpItem) {
			TunnelRequestHttpItem c = (TunnelRequestHttpItem) o;
			if (
					Objects.equals(c.targetDescription, this.targetDescription)
					&& Objects.equals(c.targetHostname, this.targetHostname)
					&& Objects.equals(c.targetPort, this.targetPort)
					&& Objects.equals(c.targetUrl, this.targetUrl)
					&& Objects.equals(c.unsafe, this.unsafe)
				) {
				return true;
			} else {
				return false;
			}			
		} else {
			return false;
		}
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("TunnelRequestHttpItem [");
		if (targetUrl != null)
			builder.append("targetUrl=").append(targetUrl).append(", ");
		builder.append("unsafe=").append(unsafe).append(", ");
		if (targetDescription != null)
			builder.append("targetDescription=").append(targetDescription).append(", ");
		if (targetHostname != null)
			builder.append("targetHostname=").append(targetHostname).append(", ");
		builder.append("targetPort=").append(targetPort).append(", targetId=").append(targetId);
		builder.append("]");
		return builder.toString();
	}
	
	public URL getTargetUrl() {
		return targetUrl;
	}

	public boolean isUnsafe() {
		return unsafe;
	}

	public String getListenHostname() {
		return listenHostname.get();
	}
	
	public void setListenHostname(String value) {
		listenHostname.set(value);
	}
	
}
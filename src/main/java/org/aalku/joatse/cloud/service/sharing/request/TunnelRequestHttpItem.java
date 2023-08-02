package org.aalku.joatse.cloud.service.sharing.request;

import java.net.URL;
import java.util.Objects;
import java.util.Optional;

public class TunnelRequestHttpItem extends TunnelRequestItem {
	public final URL targetUrl;
	public final boolean unsafe; // Allow unsafe https
	public TunnelRequestHttpItem(long targetId, String targetDescription, URL targetUrl, boolean unsafe) {
		super(targetId, targetDescription, targetUrl.getHost(),
				Optional.of(targetUrl.getPort()).map(p -> p <= 0 ? targetUrl.getDefaultPort() : p).get());
		this.targetUrl = targetUrl;
		this.unsafe = unsafe;
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
		builder.append("targetPort=").append(targetPort).append(", targetId=").append(targetId).append("]");
		return builder.toString();
	}
	
	
}
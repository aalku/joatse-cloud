package org.aalku.joatse.cloud.service.sharing.request;

import java.util.Objects;

public class TunnelRequestTcpItem extends TunnelRequestItem {
	public TunnelRequestTcpItem(long targetId, String targetDescription, String targetHostname, int targetPort) {
		super(targetId, targetDescription, targetHostname, targetPort);
	}

	@Override
	public boolean equals(TunnelRequestItem o) {
		if (o instanceof TunnelRequestTcpItem) {
			TunnelRequestTcpItem c = (TunnelRequestTcpItem) o;
			if (
					Objects.equals(c.targetDescription, this.targetDescription)
					&& Objects.equals(c.targetHostname, this.targetHostname)
					&& Objects.equals(c.targetPort, this.targetPort)
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
		builder.append("TunnelRequestTcpItem [");
		if (targetDescription != null)
			builder.append("targetDescription=").append(targetDescription).append(", ");
		if (targetHostname != null)
			builder.append("targetHostname=").append(targetHostname).append(", ");
		builder.append("targetPort=").append(targetPort).append(", targetId=").append(targetId).append("]");
		return builder.toString();
	}
	
	

}
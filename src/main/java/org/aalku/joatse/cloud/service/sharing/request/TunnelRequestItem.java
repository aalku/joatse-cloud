package org.aalku.joatse.cloud.service.sharing.request;

public abstract class TunnelRequestItem {
	public final String targetDescription;
	public final String targetHostname;
	public final int targetPort;
	/**
	 * Random target port id, to id target tuple [host, port]
	 */
	public long targetId;

	public TunnelRequestItem(long targetId, String targetDescription, String targetHostname, int targetPort) {
		this.targetId = targetId;
		this.targetHostname = targetHostname;
		this.targetPort = targetPort;
		this.targetDescription = targetDescription;
	}
	
	public abstract boolean equals(TunnelRequestItem obj);
}
package org.aalku.joatse.cloud.service.sharing.shared;

public class TcpTunnel {
	/**
	 * 
	 */
	private final SharedResourceLot sharedResourceLot;

	/**
	 * Random target port id, to id target tuple [host, port]
	 */
	public final long targetId;
	public final String targetDescription;
	public final String targetHostname;
	public final int targetPort;
	private int listenPort;

	public TcpTunnel(SharedResourceLot sharedResourceLot, long targetId, String targetDescription, String targetHostname, int targetPort) {
		this.sharedResourceLot = sharedResourceLot;
		this.targetId = targetId;
		this.targetDescription = targetDescription;
		this.targetHostname = targetHostname;
		this.targetPort = targetPort;
	}
	public SharedResourceLot getTunnel() {
		return this.sharedResourceLot;
	}

	public int getListenPort() {
		return listenPort;
	}
	public void setListenPort(int listenPort) {
		this.listenPort = listenPort;
	}
}
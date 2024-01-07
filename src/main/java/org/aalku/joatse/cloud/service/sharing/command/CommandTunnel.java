package org.aalku.joatse.cloud.service.sharing.command;

import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;

public class CommandTunnel {

	private String[] command;

	private final SharedResourceLot tunnel;

	private final long targetId;

	private final String targetDescription;

	private final String targetHostname;
	
	private final int targetPort;

	private final String targetUser;

	public CommandTunnel(SharedResourceLot tunnel, long targetId, String targetDescription, String targetHost, int targetPort, String targetUser, String[] command) {
		this.tunnel = tunnel;
		this.targetId = targetId;
		this.targetDescription = targetDescription;
		this.targetHostname = targetHost;
		this.targetPort = targetPort;
		this.targetUser = targetUser;
		this.command = command;
	}

	public String[] getCommand() {
		return command;
	}

	public SharedResourceLot getSharedResourceLot() {
		return tunnel;
	}

	public long getTargetId() {
		return targetId;
	}

	public String getTargetDescription() {
		return targetDescription;
	}

	public String getTargetHostname() {
		return targetHostname;
	}

	public int getTargetPort() {
		return targetPort;
	}

	public String getTargetUser() {
		return targetUser;
	}

}
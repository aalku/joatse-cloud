package org.aalku.joatse.cloud.service.sharing.request;

import java.util.Arrays;

public class TunnelRequestCommandItem extends TunnelRequestItem {

	private final String[] command;
	private final String user;

	public TunnelRequestCommandItem(long targetId, String targetDescription, String targetHostname, int targetPort, String user, String[] command) {
		super(targetId, targetDescription, targetHostname, targetPort);
		this.command = command;
		this.user = user;
	}

	@Override
	public boolean equals(TunnelRequestItem obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		TunnelRequestCommandItem other = (TunnelRequestCommandItem) obj;
		return Arrays.equals(command, other.command);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(command);
		return result;
	}

	public String[] getCommand() {
		return command;
	}

	public String getTargetUser() {
		return user;
	}

}

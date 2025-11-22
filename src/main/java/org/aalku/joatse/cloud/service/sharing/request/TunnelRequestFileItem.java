package org.aalku.joatse.cloud.service.sharing.request;

import java.util.Objects;

public class TunnelRequestFileItem extends TunnelRequestItem {
	
	private final String targetPath;
	
	public TunnelRequestFileItem(long targetId, String targetDescription, String targetPath) {
		super(targetId, targetDescription, null, -1); // No hostname/port for files
		this.targetPath = targetPath;
	}
	
	public String getTargetPath() {
		return targetPath;
	}
	
	@Override
	public boolean equals(TunnelRequestItem obj) {
		if (!(obj instanceof TunnelRequestFileItem)) {
			return false;
		}
		TunnelRequestFileItem other = (TunnelRequestFileItem) obj;
		return this.targetId == other.targetId 
			&& Objects.equals(this.targetPath, other.targetPath);
	}
	
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("TunnelRequestFileItem [");
		if (targetPath != null)
			builder.append("targetPath=").append(targetPath).append(", ");
		if (targetDescription != null)
			builder.append("targetDescription=").append(targetDescription).append(", ");
		builder.append("targetId=").append(targetId);
		builder.append("]");
		return builder.toString();
	}
}

package org.aalku.joatse.cloud.service.sharing.request;

import java.util.Objects;

public class TunnelRequestFileItem extends TunnelRequestItem {
	
	private final String targetPath;
	private final String targetFileName;
	
	public TunnelRequestFileItem(long targetId, String targetDescription, String targetPath, String targetFileName) {
		super(targetId, targetDescription, null, -1); // No hostname/port for files
		this.targetPath = targetPath;
		this.targetFileName = targetFileName;
	}
	
	public String getTargetPath() {
		return targetPath;
	}
	
	public String getTargetFileName() {
		return targetFileName;
	}
	
	@Override
	public boolean equals(TunnelRequestItem obj) {
		if (!(obj instanceof TunnelRequestFileItem)) {
			return false;
		}
		TunnelRequestFileItem other = (TunnelRequestFileItem) obj;
		return Objects.equals(this.targetDescription, other.targetDescription)
			&& Objects.equals(this.targetPath, other.targetPath)
			&& Objects.equals(this.targetFileName, other.targetFileName);
	}
	
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("TunnelRequestFileItem [");
		if (targetPath != null)
			builder.append("targetPath=").append(targetPath).append(", ");
		if (targetFileName != null)
			builder.append("targetFileName=").append(targetFileName).append(", ");
		if (targetDescription != null)
			builder.append("targetDescription=").append(targetDescription).append(", ");
		builder.append("targetId=").append(targetId);
		builder.append("]");
		return builder.toString();
	}
}

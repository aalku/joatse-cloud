package org.aalku.joatse.cloud.service.sharing.request;

import java.util.Objects;

public class TunnelRequestFolderItem extends TunnelRequestItem {
	
	private final String targetPath;
	private final boolean readOnly;
	
	public TunnelRequestFolderItem(long targetId, String targetDescription, String targetPath, boolean readOnly) {
		super(targetId, targetDescription, null, -1); // No hostname/port for folders
		this.targetPath = targetPath;
		this.readOnly = readOnly;
	}
	
	public String getTargetPath() {
		return targetPath;
	}
	
	public boolean isReadOnly() {
		return readOnly;
	}
	
	@Override
	public boolean equals(TunnelRequestItem obj) {
		if (!(obj instanceof TunnelRequestFolderItem)) {
			return false;
		}
		TunnelRequestFolderItem other = (TunnelRequestFolderItem) obj;
		return Objects.equals(this.targetDescription, other.targetDescription)
			&& Objects.equals(this.targetPath, other.targetPath)
			&& this.readOnly == other.readOnly;
	}
	
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("TunnelRequestFolderItem [");
		if (targetPath != null)
			builder.append("targetPath=").append(targetPath).append(", ");
		builder.append("readOnly=").append(readOnly).append(", ");
		if (targetDescription != null)
			builder.append("targetDescription=").append(targetDescription).append(", ");
		builder.append("targetId=").append(targetId);
		builder.append("]");
		return builder.toString();
	}
}

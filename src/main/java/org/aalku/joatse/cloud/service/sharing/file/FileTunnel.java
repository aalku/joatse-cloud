package org.aalku.joatse.cloud.service.sharing.file;

import java.net.URL;
import java.util.Optional;

import org.aalku.joatse.cloud.service.sharing.http.ListenAddress;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;

public class FileTunnel {
	
	private final SharedResourceLot sharedResourceLot;
	private final long targetId;
	private final String targetDescription;
	private final String targetPath;
	private ListenAddress listenAddress;
	
	public FileTunnel(SharedResourceLot sharedResourceLot, long targetId, String targetDescription, String targetPath) {
		this.sharedResourceLot = sharedResourceLot;
		this.targetId = targetId;
		this.targetDescription = targetDescription;
		this.targetPath = targetPath;
	}
	
	public SharedResourceLot getSharedResourceLot() {
		return sharedResourceLot;
	}
	
	public long getTargetId() {
		return targetId;
	}
	
	public String getTargetDescription() {
		return targetDescription;
	}
	
	public String getTargetPath() {
		return targetPath;
	}
	
	public ListenAddress getListenAddress() {
		return listenAddress;
	}
	
	public void setListenAddress(ListenAddress listenAddress) {
		this.listenAddress = listenAddress;
	}
	
	public URL getListenUrl() {
		if (listenAddress == null) {
			return null;
		}
		// Extract filename from targetPath
		String filename = extractFilename(targetPath);
		return listenAddress.getListenUrl(Optional.of("/" + filename));
	}
	
	private String extractFilename(String path) {
		if (path == null || path.isEmpty()) {
			return "file";
		}
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash >= 0 && lastSlash < path.length() - 1) {
			return path.substring(lastSlash + 1);
		}
		return path;
	}
}

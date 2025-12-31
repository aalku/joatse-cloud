package org.aalku.joatse.cloud.service.sharing.folder;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import org.aalku.joatse.cloud.service.sharing.http.ListenAddress;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;

public class FolderTunnel {
	
	private static final int PATH_HASH_LENGTH = 8;
	private static final String HASH_DICTIONARY = "abcdefghmprstxyz";
	
	private final SharedResourceLot sharedResourceLot;
	private final long targetId;
	private final String targetDescription;
	private final String targetPath;
	private final boolean readOnly;
	private ListenAddress listenAddress;
	
	public FolderTunnel(SharedResourceLot sharedResourceLot, long targetId, String targetDescription, String targetPath, boolean readOnly) {
		this.sharedResourceLot = sharedResourceLot;
		this.targetId = targetId;
		this.targetDescription = targetDescription;
		this.targetPath = targetPath;
		this.readOnly = readOnly;
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
	
	public boolean isReadOnly() {
		return readOnly;
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
		// Generate a hash prefix to ensure URL uniqueness even with same folder names
		String hashPrefix = generatePathHash();
		// Use targetDescription for the URL path - it's provided by target or generated from folder name
		String folderName = (targetDescription != null && !targetDescription.isEmpty()) ? targetDescription : "folder";
		// Path format: /<hash>/<foldername>/
		// ListenAddress.getListenUrl handles URL encoding internally
		return listenAddress.getListenUrl(Optional.of(hashPrefix + "/" + folderName + "/"));
	}
	
	/**
	 * Generates a hash prefix for the URL path to ensure uniqueness.
	 * Uses requester address and target path to differentiate between:
	 * - Same folder from different sessions/users
	 * - Different folders with the same name
	 */
	private String generatePathHash() {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			// Include requester address to differentiate sessions
			if (sharedResourceLot != null && sharedResourceLot.getRequesterAddress() != null 
					&& sharedResourceLot.getRequesterAddress().getAddress() != null) {
				md.update(sharedResourceLot.getRequesterAddress().getAddress().getAddress());
			}
			// Include target path to differentiate different folders
			if (targetPath != null) {
				md.update(targetPath.getBytes(StandardCharsets.UTF_8));
			}
			// Include targetId for additional uniqueness within a session
			md.update(new byte[] {
				(byte)(targetId & 0xff), 
				(byte)((targetId >> 8) & 0xff),
				(byte)((targetId >> 16) & 0xff),
				(byte)((targetId >> 24) & 0xff)
			});
			// Include readOnly flag
			md.update((byte)(readOnly ? 1 : 0));
			byte[] digest = md.digest();
			StringBuilder sb = new StringBuilder(PATH_HASH_LENGTH);
			for (int i = 0; i < PATH_HASH_LENGTH; i++) {
				char c = HASH_DICTIONARY.charAt(digest[i] & 0x0F);
				sb.append(c);
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}
}

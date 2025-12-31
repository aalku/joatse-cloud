package org.aalku.joatse.cloud.service.sharing.file;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import org.aalku.joatse.cloud.service.sharing.http.ListenAddress;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;

public class FileTunnel {
	
	private static final int PATH_HASH_LENGTH = 8;
	private static final String HASH_DICTIONARY = "abcdefghmprstxyz";
	
	private final SharedResourceLot sharedResourceLot;
	private final long targetId;
	private final String targetDescription;
	private final String targetPath;
	private final String targetFileName;
	private ListenAddress listenAddress;
	
	public FileTunnel(SharedResourceLot sharedResourceLot, long targetId, String targetDescription, String targetPath, String targetFileName) {
		this.sharedResourceLot = sharedResourceLot;
		this.targetId = targetId;
		this.targetDescription = targetDescription;
		this.targetPath = targetPath;
		this.targetFileName = targetFileName;
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
	
	public String getTargetFileName() {
		return targetFileName;
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
		// Generate a hash prefix to ensure URL uniqueness even with same filenames
		String hashPrefix = generatePathHash();
		// Use targetFileName directly - it's already extracted by the target
		String filename = (targetFileName != null && !targetFileName.isEmpty()) ? targetFileName : "file";
		// Path format: /<hash>/<filename>
		// ListenAddress.getListenUrl handles URL encoding internally
		return listenAddress.getListenUrl(Optional.of(hashPrefix + "/" + filename));
	}
	
	/**
	 * Generates a hash prefix for the URL path to ensure uniqueness.
	 * Uses requester address and target path to differentiate between:
	 * - Same file from different sessions/users
	 * - Different files with the same name
	 */
	private String generatePathHash() {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			// Include requester address to differentiate sessions
			if (sharedResourceLot != null && sharedResourceLot.getRequesterAddress() != null 
					&& sharedResourceLot.getRequesterAddress().getAddress() != null) {
				md.update(sharedResourceLot.getRequesterAddress().getAddress().getAddress());
			}
			// Include target path to differentiate different files
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

package org.aalku.joatse.cloud.service.sharing;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.sharing.file.FileTunnel;
import org.aalku.joatse.cloud.service.sharing.http.HttpTunnel;
import org.aalku.joatse.cloud.service.sharing.http.ListenAddress;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.service.sharing.shared.TcpTunnel;
import org.aalku.joatse.cloud.tools.net.AddressRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TunnelRegistry {
	
	static Logger log = LoggerFactory.getLogger(TunnelRegistry.class);

	private Map<UUID, SharedResourceLot> tunnelsByUUID = new LinkedHashMap<>();
			
	public synchronized List<HttpTunnel> findMatchingHttpTunnel(InetAddress remoteAddress, int serverPort, String serverName, String protocol) {
		ListenAddress reachedAddress = new ListenAddress(serverPort, serverName, protocol);
		List<HttpTunnel> tunnelsMatching = tunnelsByUUID.values().stream()
				.flatMap(x -> x.getHttpItems().stream())
				.filter(http->http.getListenAddress().equals(reachedAddress))
				.filter(http -> {
					SharedResourceLot t = http.getTunnel();
					if (t.isAutoAuthorizeByHttpUrl()) {
						return true;
					} else {
						// Use flexible IP address matching (supports exact IP, CIDR, and wildcards)
						return isAddressAllowed(remoteAddress, t);
					}
				})
				.collect(Collectors.toList());
		log.debug("Found {} HTTP tunnel(s) matching: {}", tunnelsMatching.size(), reachedAddress);
		return tunnelsMatching;
	}
	
	public synchronized List<TcpTunnel> findMatchingTcpTunnel(InetAddress remoteAddress, int listenPort) {
		List<TcpTunnel> tunnelsMatching = tunnelsByUUID.values().stream()
				.filter(t -> {
					// Use flexible IP address matching (supports exact IP, CIDR, and wildcards)
					return isAddressAllowed(remoteAddress, t);
				})
				.flatMap(x -> x.getTcpItems().stream())
				.filter(tcp->tcp.getListenPort() == listenPort)
				.collect(Collectors.toList());
		return tunnelsMatching;
	}
	
	public synchronized List<FileTunnel> findMatchingFileTunnel(InetAddress remoteAddress, int serverPort, String serverName, String protocol, String requestPath) {
		ListenAddress reachedAddress = new ListenAddress(serverPort, serverName, protocol);
		
		List<FileTunnel> tunnelsMatching = tunnelsByUUID.values().stream()
				.flatMap(x -> x.getFileItems().stream())
				.filter(file->{
					ListenAddress fileAddress = file.getListenAddress();
					if (fileAddress == null) {
						log.warn("FileTunnel has null ListenAddress - targetId={}, targetDescription={}, targetPath={}", 
							file.getTargetId(), file.getTargetDescription(), file.getTargetPath());
						return false;
					}
					// Check if ListenAddress matches
					if (!fileAddress.equals(reachedAddress)) {
						return false;
					}
					// Check if request path matches the file's URL path
					if (requestPath != null && file.getListenUrl() != null) {
						String filePath = file.getListenUrl().getPath();
						return requestPath.equals(filePath);
					}
					return true;
				})
				.filter(file -> {
					SharedResourceLot t = file.getSharedResourceLot();
					if (t.isAutoAuthorizeByHttpUrl()) {
						return true;
					} else {
						// Use flexible IP address matching (supports exact IP, CIDR, and wildcards)
						return isAddressAllowed(remoteAddress, t);
					}
				})
				.collect(Collectors.toList());
		log.debug("Found {} file tunnel(s) matching: {}", tunnelsMatching.size(), reachedAddress);
		return tunnelsMatching;
	}
	
	@SuppressWarnings("unchecked")
	public synchronized <E> E getTunnel(UUID uuid, long targetId) {
		SharedResourceLot srl = tunnelsByUUID.get(uuid);
		if (srl == null) {
			return null;
		} else {
			Object res = srl.getHttpItem(targetId);
			if (res == null) {
				res = srl.getTcpItem(targetId);
			}
			if (res == null) {
				res = srl.getCommandItem(targetId);
			}
			if (res == null) {
				res = srl.getFileItem(targetId);
			}
			return (E) res;
		}
	}

	public synchronized void removeTunnel(UUID uuid) {
		tunnelsByUUID.remove(uuid);
	}

	public synchronized void registerTunnel(SharedResourceLot tunnel) {
		tunnelsByUUID.put(tunnel.getUuid(), tunnel);
	}
	
	/**
	 * Checks if the given remote address is allowed to access the tunnel.
	 * Uses AddressRange for flexible IP address matching supporting exact IPs, CIDR notation, and wildcards.
	 */
	private boolean isAddressAllowed(InetAddress remoteAddress, SharedResourceLot tunnel) {
		// Use AddressRange collection for flexible matching
		if (!tunnel.getAllowedAddressRanges().isEmpty()) {
			return tunnel.getAllowedAddressRanges().stream()
				.anyMatch(addressRange -> addressRange.matches(remoteAddress));
		}
		
		// If no address ranges configured, deny access
		return false;
	}

}

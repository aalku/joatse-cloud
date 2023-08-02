package org.aalku.joatse.cloud.service.sharing;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.sharing.http.HttpTunnel;
import org.aalku.joatse.cloud.service.sharing.http.ListenAddress;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.service.sharing.shared.TcpTunnel;
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
				.filter(t -> t.getAllowedAddresses().contains(remoteAddress))
				.flatMap(x -> x.getHttpItems().stream())
				.filter(http->http.getListenAddress().equals(reachedAddress))
				.collect(Collectors.toList());
		return tunnelsMatching;
	}
	
	public synchronized List<TcpTunnel> findMatchingTcpTunnel(InetAddress remoteAddress, int listenPort) {
		List<TcpTunnel> tunnelsMatching = tunnelsByUUID.values().stream()
				.filter(t -> t.getAllowedAddresses().contains(remoteAddress))
				.flatMap(x -> x.getTcpItems().stream())
				.filter(tcp->tcp.getListenPort() == listenPort)
				.collect(Collectors.toList());
		return tunnelsMatching;
	}
	
	public synchronized HttpTunnel getHttpTunnel(UUID uuid, long targetId) {
		return Optional.ofNullable(tunnelsByUUID.get(uuid)).map(t -> t.getHttpItem(targetId)).orElse(null);
	}

	public synchronized void removeTunnel(UUID uuid) {
		tunnelsByUUID.remove(uuid);
	}

	public synchronized void registerTunnel(SharedResourceLot tunnel) {
		tunnelsByUUID.put(tunnel.getUuid(), tunnel);
	}
	

}

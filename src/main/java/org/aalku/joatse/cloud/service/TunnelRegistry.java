package org.aalku.joatse.cloud.service;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel.TcpTunnel;
import org.aalku.joatse.cloud.service.HttpProxy.HttpTunnel;
import org.aalku.joatse.cloud.tools.io.PortRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class TunnelRegistry {
	
	private Map<Integer, Collection<HttpTunnel>> httpTunnelsByPort = new LinkedHashMap<>();

	private Map<UUID, JoatseTunnel> tunnelsByUUID = new LinkedHashMap<>();
	
	private Map<Integer, List<JoatseTunnel.TcpTunnel>> tcpTunnelsByPortMap = new LinkedHashMap<>();
	
	@Autowired
	@Qualifier("httpPortRange")
	private PortRange httpPortRange;
	
	@Autowired
	@Qualifier("httpHosts")
	private Set<String> httpHosts;

	public synchronized InetSocketAddress findAvailableHttpPort(Collection<InetAddress> allowedAddress,
			Map<String, Set<Integer>> forbiddenPortMap) {
		for (int p = httpPortRange.min(); p < httpPortRange.max(); p++) {
			for (String host : httpHosts) {
				Set<Integer> forbiddenPorts = forbiddenPortMap.computeIfAbsent(host, x -> new HashSet<>());
				if (forbiddenPorts.contains(p)) {
					continue;
				}
				boolean freeSlot = Optional.ofNullable(httpTunnelsByPort.get(p)).orElse(Collections.emptyList())
						.stream().filter(t -> t.getCloudHostname().equals(host))
						.filter(t -> t.getTunnel().getAllowedAddress().stream().filter(a -> {
							return allowedAddress.contains(a);
						}).findAny().isPresent()).findAny().isEmpty();
				if (freeSlot) {
					return InetSocketAddress.createUnresolved(host, p);
				}
			}
		}
		return null;
	}

	public synchronized List<HttpTunnel> findMatchingHttpTunnel(InetAddress remoteAddress, int serverPort, String serverName) {
		List<HttpTunnel> tunnelsMatching = Optional.ofNullable(httpTunnelsByPort.get(serverPort))
				.orElse(Collections.emptyList()).stream()
				.filter((HttpTunnel t) -> t.getTunnel().getAllowedAddress().contains(remoteAddress))
				.filter((HttpTunnel t) -> {
					return t.getCloudHostname().equals(serverName);
				}).collect(Collectors.toList());
		return tunnelsMatching;
	}
	
	public synchronized List<TcpTunnel> findMatchingTcpTunnel(InetAddress remoteAddress, int serverPort) {
		List<TcpTunnel> tunnelsMatching = Optional.ofNullable(tcpTunnelsByPortMap.get(serverPort))
				.orElse(Collections.emptyList()).stream()
				.filter((TcpTunnel t) -> t.getTunnel().getAllowedAddress().contains(remoteAddress))
				.collect(Collectors.toList());
		return tunnelsMatching;
	}
	
	public synchronized HttpTunnel getHttpTunnel(UUID uuid, long targetId) {
		return Optional.ofNullable(tunnelsByUUID.get(uuid)).map(t -> t.getHttpItem(targetId)).orElse(null);
	}

	public synchronized void removeTunnel(UUID uuid) {
		JoatseTunnel td = tunnelsByUUID.remove(uuid);
		if (td != null) {
			for (JoatseTunnel.TcpTunnel i: td.getTcpItems()) {
				tcpTunnelsByPortMap.get(i.listenPort).remove(i);
			}
			for (HttpTunnel i: td.getHttpItems()) {
				httpTunnelsByPort.get(i.getListenPort()).remove(i);
			}
		}
	}

	public synchronized void registerTunnel(JoatseTunnel tunnel) {
		tunnelsByUUID.put(tunnel.getUuid(), tunnel);
		for (TcpTunnel item: tunnel.getTcpItems()) {
			tcpTunnelsByPortMap.computeIfAbsent(item.listenPort, x -> new ArrayList<>()).add(item);
		}
		for (HttpTunnel item: tunnel.getHttpItems()) {
			httpTunnelsByPort.computeIfAbsent(item.getListenPort(), k -> new ArrayList<>()).add(item);
		}
	}

}

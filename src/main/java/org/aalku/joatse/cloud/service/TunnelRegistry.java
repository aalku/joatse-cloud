package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel.TcpTunnel;
import org.aalku.joatse.cloud.service.HttpProxy.HttpTunnel;
import org.aalku.joatse.cloud.tools.io.PortRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class TunnelRegistry {
	
	static Logger log = LoggerFactory.getLogger(TunnelRegistry.class);

	private Map<UUID, JoatseTunnel> tunnelsByUUID = new LinkedHashMap<>();
	
	@Autowired
	@Qualifier("httpPortRange")
	private PortRange httpPortRange;
	
	@Autowired
	@Qualifier("httpUnsafePortRange")
	private PortRange httpUnsafePortRange;

	@Autowired
	@Qualifier("httpHosts")
	private Set<String> httpHosts;

	private Collection<Integer> tcpOpenPorts = null;
	
	@Autowired
	private ListenerConfigurationDetector webListenerConfigurationDetector;
	
	private synchronized InetSocketAddress findAvailableHttpServerAddress(HttpTunnel tunnel, InetAddress remoteAddress) {
		PortRange portRange = tunnel.isUnsafe() ? httpUnsafePortRange : httpPortRange;
		for (int p = portRange.min(); p < portRange.max(); p++) {
			final int _p = p;
			for (String host : httpHosts) {
				boolean freeSlot = tunnelsByUUID.values().stream()
						.filter(t -> t.getAllowedAddresses().contains(remoteAddress))
						.flatMap(x -> x.getHttpItems().stream())
						.filter(t -> t.matches(remoteAddress, _p, host))
						.findAny().isEmpty();
				if (freeSlot) {
					return InetSocketAddress.createUnresolved(host, p);
				}
			}
		}
		return null;
	}
	
	private synchronized Integer findAvailableTcpPort(TcpTunnel tunnel, InetAddress remoteAddress) {
		for (int p: tcpOpenPorts) {
			final int _p = p;
			boolean freeSlot = tunnelsByUUID.values().stream()
					.filter(t -> t.getAllowedAddresses().contains(remoteAddress))
					.flatMap(x -> x.getTcpItems().stream())
					.filter(t -> t.matches(remoteAddress, _p))
					.findAny().isEmpty();
			if (freeSlot) {
				return p;
			}
		}
		return null;
	}


	public synchronized List<HttpTunnel> findMatchingHttpTunnel(InetAddress remoteAddress, int serverPort, String serverName) {
		List<HttpTunnel> tunnelsMatching = tunnelsByUUID.values().stream()
				.filter(t -> t.getAllowedAddresses().contains(remoteAddress))
				.flatMap(x -> x.getHttpItems().stream())
				.filter(http->http.matches(remoteAddress, serverPort, serverName))
				.collect(Collectors.toList());
		return tunnelsMatching;
	}
	
	public synchronized List<TcpTunnel> findMatchingTcpTunnel(InetAddress remoteAddress, int listenPort) {
		List<TcpTunnel> tunnelsMatching = tunnelsByUUID.values().stream()
				.filter(t -> t.getAllowedAddresses().contains(remoteAddress))
				.flatMap(x -> x.getTcpItems().stream())
				.filter(tcp->tcp.matches(remoteAddress, listenPort))
				.collect(Collectors.toList());
		return tunnelsMatching;
	}
	
	public synchronized HttpTunnel getHttpTunnel(UUID uuid, long targetId) {
		return Optional.ofNullable(tunnelsByUUID.get(uuid)).map(t -> t.getHttpItem(targetId)).orElse(null);
	}

	public synchronized void removeTunnel(UUID uuid) {
		tunnelsByUUID.remove(uuid);
	}

	public synchronized void registerTunnel(JoatseTunnel tunnel) {
		final ArrayList<Runnable> transactionAbortHandlers = new ArrayList<>();
		tunnelsByUUID.put(tunnel.getUuid(), tunnel);
		transactionAbortHandlers.add(()->tunnelsByUUID.remove(tunnel.getUuid()));
		/* 
		 * Determine each listen port for each allowed address and each tunnel
		 */
		try {
			for (InetAddress allowedAddress: tunnel.getAllowedAddresses()) {
				allowRemoteAddress(tunnel, transactionAbortHandlers, allowedAddress);
			}
		} catch (Exception e) {
			log.warn("Exception registerring tunnel: " + e, e);
			for (Runnable task: transactionAbortHandlers) {
				try {
					task.run();
				} catch (Exception e1) {
					log.error("Exception running transaction abort handler: " + e1, e1);
				}
			}
		}
	}
	
	public synchronized void registerRemoteAddress(JoatseTunnel tunnel, InetAddress allowedAddress) {
		final ArrayList<Runnable> transactionAbortHandlers = new ArrayList<>();
		if (!tunnel.getAllowedAddresses().contains(allowedAddress)) {
			throw new IllegalStateException("Add the address to tunnel allowed addressess first");
		}
		/* 
		 * Determine each listen port for each allowed address and each tunnel
		 */
		try {
			allowRemoteAddress(tunnel, transactionAbortHandlers, allowedAddress);
		} catch (Exception e) {
			log.warn("Exception registerring tunnel: " + e, e);
			for (Runnable task: transactionAbortHandlers) {
				try {
					task.run();
				} catch (Exception e1) {
					log.error("Exception running transaction abort handler: " + e1, e1);
				}
			}
		}
	}


	private void allowRemoteAddress(JoatseTunnel tunnel, final ArrayList<Runnable> transactionAbortHandlers,
			InetAddress allowedAddress) throws IOException {
		String protocol = webListenerConfigurationDetector.isSslEnabled() ? "https" : "http";
		for (TcpTunnel item: tunnel.getTcpItems()) {
			Integer port = this.findAvailableTcpPort(item, allowedAddress);
			if (port == null) {
				throw new IOException("Can't find an available tcp port to listen from " + allowedAddress);
			}
			item.registerListenAllowedAddress(allowedAddress, port);
			transactionAbortHandlers.add(()->item.unregisterListenAllowedAddress(allowedAddress));
		}
		for (HttpTunnel item: tunnel.getHttpItems()) {
			InetSocketAddress serverAddress = this.findAvailableHttpServerAddress(item, allowedAddress);
			if (serverAddress == null) {
				throw new IOException("Can't find an available http address to listen from " + allowedAddress);
			}
			item.registerListenAllowedAddress(allowedAddress, serverAddress.getHostString(), serverAddress.getPort(), protocol);
			transactionAbortHandlers.add(()->item.unregisterListenAllowedAddress(allowedAddress));
		}
	}

	public void setTcpOpenPorts(Collection<Integer> tcpOpenPorts) {
		this.tcpOpenPorts = tcpOpenPorts;
	}
}

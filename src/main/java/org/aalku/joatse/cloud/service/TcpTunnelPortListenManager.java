package org.aalku.joatse.cloud.service;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel.TcpTunnel;
import org.aalku.joatse.cloud.tools.io.AsyncTcpPortListener;
import org.aalku.joatse.cloud.tools.io.AsyncTcpPortListener.Event;
import org.aalku.joatse.cloud.tools.io.IOTools;
import org.aalku.joatse.cloud.tools.io.PortRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TcpTunnelPortListenManager implements InitializingBean, DisposableBean {
	
	private Logger log = LoggerFactory.getLogger(TcpTunnelPortListenManager.class);

	/**
	 * Map of open server sockets. Remember there can be several connections from
	 * different users on the same port.
	 */
	private Map<Integer, AsyncTcpPortListener<Void>> openPortMap = new LinkedHashMap<>();
	
	@Qualifier("openPortRange")
	@Autowired
	public PortRange openPortRange;
	
	@Value("${server.hostname.listen:0.0.0.0}")
	private String listenHostname;

	@Autowired
	private TunnelRegistry tunnelRegistry;

	private void setupPortListen() throws UnknownHostException {
		for (int p = openPortRange.min(); p <= openPortRange.max(); p++) {
			setupPortListen(p);
		}
		tunnelRegistry.setTcpOpenPorts(new ArrayList<>(openPortMap.keySet()));
	}

	private void setupPortListen(int port) throws UnknownHostException {
		
		AsyncTcpPortListener<Void> asyncTcpPortListener = new AsyncTcpPortListener<Void>(
				InetAddress.getByName(listenHostname), port, null,
				(Consumer<Event<Void>>) new Consumer<AsyncTcpPortListener.Event<Void>>() {
					@Override
					public void accept(Event<Void> t) {
						if (t.error != null) {
							log.error(String.format("Could not accept connections on tcp port %s: %s", port, t.error.toString()), t.error);
							synchronized (openPortMap) {
								openPortMap.remove(port);
								/*
								 * TODO Tell someone.
								 * 
								 * We have to detect who's this connection for. Remember there can be several
								 * connections from different users on the same port.
								 */
							}
						} else if (t.channel != null) {
							try {
								InetSocketAddress remoteAddress = (InetSocketAddress) IOTools
										.runUnchecked(() -> t.channel.getRemoteAddress());
								/*
								 * We have to detect who's this connection for. Remember there can be several
								 * connections from different users on the same port.
								 */
								List<JoatseTunnel.TcpTunnel> matching = tunnelRegistry
										.findMatchingTcpTunnel(remoteAddress.getAddress(), port);
								if (matching.size() == 1) {
									TcpTunnel tcpTunnel = matching.get(0);
									JoatseTunnel tunnel = tcpTunnel.getTunnel();
									log.info("Accepted connection from {}.{} on port {}", remoteAddress,
											tcpTunnel.targetId, port);
									tunnel.tunnelTcpConnection(tcpTunnel.targetId, t.channel);
								} else {
									// Not accepted then rejected
									log.warn("Rejected connection from {} at port {}", remoteAddress, port);
									IOTools.closeChannel(t.channel);
								}
							} catch (Exception e) {
								log.warn("Error handling incomming socket: {}", e, e);
								IOTools.closeChannel(t.channel);
							}
						} else {
							log.info("Closed tcp port {}", port);
							synchronized (openPortMap) {
								openPortMap.remove(port);
							}
						}
					}
				});
		synchronized (openPortMap) {
			openPortMap.put(port, asyncTcpPortListener);
		}
	}
	
	@Override
	public void afterPropertiesSet() throws Exception {
		setupPortListen();
	}

	@Override
	public void destroy() throws Exception {
		synchronized (openPortMap) {
			for (AsyncTcpPortListener<Void> port : this.openPortMap.values()) {
				ForkJoinPool.commonPool().execute(()->port.close()); // Without lock, later
			}
			this.openPortMap.clear();
		}
	}


}

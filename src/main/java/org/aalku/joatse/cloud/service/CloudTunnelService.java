package org.aalku.joatse.cloud.service;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.channels.AsynchronousSocketChannel;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.config.WebSocketConfig;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel.TcpTunnel;
import org.aalku.joatse.cloud.service.HttpProxy.HttpTunnel;
import org.aalku.joatse.cloud.service.user.JoatseUser;
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
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Handles tunnel requests, accepted definitions, tunnel connections and
 * listening ports.
 * 
 * This service also notifies the requester if there is a match between a new
 * tcp connection and a defined tunnel.
 * 
 * A JWSSession can be mapped to nothing or a TunnelRequest or a JoatseTunnel.
 */
@Service
public class CloudTunnelService implements InitializingBean, DisposableBean {

	// TODO we need a scheduled task to cleanup tunnelRequestMap from old
	// unconfirmed requests

	public abstract static class TunnelRequestItem {
		public final String targetDescription;
		public final String targetHostname;
		public final int targetPort;
		/**
		 * Random target port id, to id target tuple [host, port]
		 */
		public long targetId;

		public TunnelRequestItem(long targetId, String targetDescription, String targetHostname, int targetPort) {
			this.targetId = targetId;
			this.targetHostname = targetHostname;
			this.targetPort = targetPort;
			this.targetDescription = targetDescription;
		}
	}

	public static class TunnelRequestTcpItem extends TunnelRequestItem {
		public TunnelRequestTcpItem(long targetId, String targetDescription, String targetHostname, int targetPort) {
			super(targetId, targetDescription, targetHostname, targetPort);
		}
	}
	
	public static class TunnelRequestHttpItem extends TunnelRequestItem {
		public final URL targetUrl;
		public TunnelRequestHttpItem(long targetId, String targetDescription, URL targetUrl) {
			super(targetId, targetDescription, targetUrl.getHost(),
					Optional.of(targetUrl.getPort()).map(p -> p <= 0 ? targetUrl.getDefaultPort() : p).get());
			this.targetUrl = targetUrl;
		}
	}

	public class TunnelRequest {
		private final UUID uuid = UUID.randomUUID();
		private final InetSocketAddress requesterAddress;
		private final Collection<TunnelRequestItem> items;
		private final Instant creationTime;
		public final CompletableFuture<TunnelCreationResult> future = new CompletableFuture<>();
		private Collection<InetAddress> allowedAddress;

		public TunnelRequest(InetSocketAddress connectionRequesterAddress, Collection<TunnelRequestItem> tunnelItems) {
			this.requesterAddress = connectionRequesterAddress;
			this.items = new ArrayList<>(tunnelItems);
			this.creationTime = Instant.now();
		}

		public InetSocketAddress getConnectionRequesterAddress() {
			return requesterAddress;
		}

		public Instant getCreationTime() {
			return creationTime;
		}

		public CompletableFuture<TunnelCreationResult> getFuture() {
			return future;
		}

		public void setAllowedAddress(Collection<InetAddress> allowedAddress) {
			this.allowedAddress = allowedAddress;
		}

		public UUID getUuid() {
			return uuid;
		}

		public Collection<InetAddress> getAllowedAddress() {
			return allowedAddress;
		}

		public Collection<TunnelRequestItem> getItems() {
			return items;
		}

	}

	public static class JoatseTunnel {

		public class TcpTunnel {
			public final int listenPort;
			/**
			 * Random target port id, to id target tuple [host, port]
			 */
			public final long targetId;
			public final String targetDescription;
			public final String targetHostname;
			public final int targetPort;
			public TcpTunnel(int listenPort, TunnelRequestTcpItem req) {
				this.targetId = req.targetId;
				this.listenPort = listenPort;
				this.targetDescription = req.targetDescription;
				this.targetHostname = req.targetHostname;
				this.targetPort = req.targetPort;
			}
			public JoatseTunnel getTunnel() {
				return JoatseTunnel.this;
			}
		}

		private final JoatseUser owner;
		private final UUID uuid;
		private final String cloudPublicHostname;
		private Collection<InetAddress> allowedAddress;
		private final InetSocketAddress requesterAddress;
		private final Instant creationTime;
		/**
		 * Handler for tcp or http(s) connections that are handled as tcp at this point
		 */
		private BiConsumer<Long, AsynchronousSocketChannel> tcpConnectionListener;
		private Collection<TcpTunnel> tcpItems = new ArrayList<>(1);
		private Collection<HttpTunnel> httpItems = new ArrayList<>(1);
		private Map<String, String> urlRewriteMap = new LinkedHashMap<>();
		private Map<String, String> urlReverseRewriteMap = new LinkedHashMap<>();

		public JoatseTunnel(JoatseUser owner, TunnelRequest request, String cloudPublicHostname) {
			this.owner = owner;
			this.uuid = request.getUuid();
			this.cloudPublicHostname = cloudPublicHostname;
			this.requesterAddress = request.requesterAddress;
			this.allowedAddress = request.getAllowedAddress();
			this.creationTime = request.getCreationTime();
		}

		public void setTcpConnectionConsumer(BiConsumer<Long, AsynchronousSocketChannel> tcpConnectionListener) {
			this.tcpConnectionListener = tcpConnectionListener;
		}

		public Collection<InetAddress> getAllowedAddress() {
			return allowedAddress;
		}

		public InetSocketAddress getRequesterAddress() {
			return requesterAddress;
		}

		public UUID getUuid() {
			return uuid;
		}

		public Instant getCreationTime() {
			return creationTime;
		}

		public JoatseUser getOwner() {
			return owner;
		}

		public void addTcpItem(int port, TunnelRequestTcpItem r) {
			TcpTunnel i = new TcpTunnel(port, r);
			tcpItems.add(i);
		}
		
		public void addHttpItem(HttpTunnel item) {
			URL tau = item.getTargetURL();
			int port = IOTools.getPort(tau);
			String protocol = tau.getProtocol();
			String host = tau.getHost();
			boolean portOptional = (port == 80 && protocol.equals("http")) || port == 443 && protocol.equals("https");
			
			String taus1 = protocol + "://" + host + ":" + port;
			Optional<String> taus2 = portOptional ? Optional.of(protocol + "://" + host) : Optional.empty();
			
			String tuu = item.getCloudProtocol() + "://" + item.getCloudHostname() + ":" + item.getListenPort();
			
			// FIXME tuu might have an optional port too
			
			urlRewriteMap.put(taus1, tuu);
			if (taus2.isPresent()) {
				urlRewriteMap.put(taus2.get(), tuu);
				urlReverseRewriteMap.put(tuu, taus2.get());
			} else {
				urlReverseRewriteMap.put(tuu, taus1);
			}
			httpItems.add(item);
		}

		public TcpTunnel getTcpItem(long targetId) {
			return getTcpItems().stream().filter(i->i.targetId==targetId).findAny().orElse(null);
		}

		public Collection<TcpTunnel> getTcpItems() {
			return tcpItems;
		}

		public String getCloudPublicHostname() {
			return cloudPublicHostname;
		}

		public Collection<HttpTunnel> getHttpItems() {
			return httpItems;
		}

		public HttpTunnel getHttpItem(long targetId) {
			return getHttpItems().stream().filter(i->i.getTargetId()==targetId).findAny().orElse(null);
		}

		/**
		 * Create a tunneled tcp connection.
		 * 
		 * It can tunnel tcp tunnels or other tunnels running over tcp so targetId might
		 * or might not represent a tcpTunnel.
		 * 
		 * @param targetId
		 * @param channel
		 */
		public void tunnelTcpConnection(long targetId, AsynchronousSocketChannel channel) {
			tcpConnectionListener.accept(targetId, channel);
		}

		public Function<String, String> getUrlRewriteFunction() {
			return u->urlRewriteMap.getOrDefault(u, u);
		}

		public Function<String, String> getUrlReverseRewriteFunction() {
			return u->urlReverseRewriteMap.getOrDefault(u, u);
		}
	}

	public static class TunnelCreationResponse {
		/** Future result of the petition of connection creation */
		CompletableFuture<TunnelCreationResult> result;
		/**
		 * Url to accept the connection petition (and take note of and confirm other
		 * connection adjustments
		 */
		String confirmationUri;
		/** IF of this connection petition/config */
		UUID uuid;

		public TunnelCreationResponse(UUID uuid, String confirmationUri,
				CompletableFuture<TunnelCreationResult> result) {
			this.uuid = uuid;
			this.confirmationUri = confirmationUri;
			this.result = result;
		}
	}

	public interface ConnectionInstanceProvider {
		void setCallback(Consumer<AsynchronousSocketChannel> callback);
	}

	public abstract static class TunnelCreationResult {
		private final UUID uuid;
		private final Collection<TunnelRequestItem> items;

		public TunnelCreationResult(TunnelRequest request) {
			this.uuid = request.uuid;
			this.items = new ArrayList<>(request.getItems());
		}

		public abstract boolean isAccepted();

		public UUID getUuid() {
			return uuid;
		}

		public Collection<TunnelRequestItem> getItems() {
			return items;
		}

		public static class Accepted extends TunnelCreationResult {
			private JoatseTunnel tunnel;

			public Accepted(TunnelRequest request, JoatseTunnel tunnel) {
				super(request);
				this.tunnel = tunnel;
			}

			public boolean isAccepted() {
				return true;
			}

			public JoatseTunnel getTunnel() {
				return tunnel;
			}
		};

		public static class Rejected extends TunnelCreationResult {
			private final String cause;

			public Rejected(TunnelRequest request, String cause) {
				super(request);
				this.cause = cause;
			}

			@Override
			public boolean isAccepted() {
				return false;
			}

			public String getRejectionCause() {
				return cause;
			}
		};
	}

	private Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

	private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	@Value("${server.hostname.listen:0.0.0.0}")
	private String listenHostname;

	@Autowired
	private ListenerConfigurationDetector webListenerConfiguration;

	@Qualifier("openPortRange")
	@Autowired
	public PortRange openPortRange;
	
	@Autowired
	private HttpProxy httpProxy;

	@Autowired
	private TunnelRegistry tunnelRegistry;

	private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

	/**
	 * Map of open server sockets. Remember there can be several connections from
	 * different users on the same port.
	 */
	private Map<Integer, AsyncTcpPortListener<Void>> openPortMap = new LinkedHashMap<>();

	/**
	 * Requested connections not confirmed yet
	 */
	Map<UUID, TunnelRequest> tunnelRequestMap = new LinkedHashMap<>();

	/**
	 * For easier testing of target only. FIXME remove this.
	 */
	private String[] automaticTunnelAcceptance = Optional
			.ofNullable(System.getProperty("automaticTunnelAcceptance", null)).map(s -> s.split("-", 2)).orElse(null);

	/**
	 * Requests a connection need. The client user must then confirm it from the ip
	 * address or class from where they want to connect to it.
	 * 
	 * @param principal
	 * @param connectionRequesterAddress 
	 * @param inetSocketAddress
	 * @param targetHostId
	 * @param targetPort
	 * @param targetPortDescription
	 * @return
	 */
	public TunnelCreationResponse requestTunnel(Principal principal, InetSocketAddress connectionRequesterAddress, Collection<TunnelRequestTcpItem> tcpTunnels, Collection<TunnelRequestHttpItem> httpTunnels) {
		lock.writeLock().lock();
		TunnelRequest request;
		try {
			Collection<TunnelRequestItem> items = new ArrayList<CloudTunnelService.TunnelRequestItem>();
			items.addAll(tcpTunnels);
			items.addAll(httpTunnels);
			request = new TunnelRequest(connectionRequesterAddress, items);
			tunnelRequestMap.put(request.uuid, request);
		} finally {
			lock.writeLock().unlock();
		}
		if (automaticTunnelAcceptance != null) { // TODO remove this mock
			new Thread() {
				public void run() {
					try {
						Thread.sleep(500);
						request.setAllowedAddress(Arrays.asList(InetAddress.getAllByName("localhost"))); // TODO
						acceptTunnelRequest(request.uuid, new JoatseUser(automaticTunnelAcceptance[0], automaticTunnelAcceptance[1]));
					} catch (InterruptedException e) {
					} catch (UnknownHostException e) {
					}
				};
			}.start();
		}
		return new TunnelCreationResponse(request.uuid, buildConfirmationUri(request), request.future);
	}

	private String buildConfirmationUri(TunnelRequest request) {
		UriComponentsBuilder ub = UriComponentsBuilder.newInstance();
		ub.scheme(webListenerConfiguration.isSslEnabled() ? "https" : "http");
		ub.host(webListenerConfiguration.getPublicHostname());
		ub.port(webListenerConfiguration.getServerPort());
		ub.path("/CF");
		return ub.toUriString() + "?ts=" + System.currentTimeMillis() + "#" + request.uuid;
	}

	/**
	 * The user accepted this connection and completed it with any needed
	 * parameters.
	 */
	public void acceptTunnelRequest(UUID uuid, JoatseUser user) {
		log.info("JoatseUser accepted tunnel request: {}", uuid);
		TunnelRequest request = null;
		TunnelCreationResult.Accepted newTunnel;
		try {
			lock.writeLock().lock();
			try {
				request = tunnelRequestMap.remove(uuid);
				if (request == null) {
					log.warn("It was accepted a request that was not waiting for acceptance: {}", uuid);
					return;
				}

				// Connect listeners
				JoatseTunnel tunnel = registerTunnel(user, request);
				newTunnel = new TunnelCreationResult.Accepted(request, tunnel);
			} finally {
				lock.writeLock().unlock();
			}
			request.future.complete(newTunnel); // Without lock
		} catch (Exception e) {
			if (request != null) {
				// Reject
				request.future.completeExceptionally(e);
				return;
			} else {
				throw e;
			}
		}
	}

	private JoatseTunnel registerTunnel(JoatseUser owner, TunnelRequest request) {
		JoatseTunnel tunnel;
		if (!lock.writeLock().isHeldByCurrentThread()) {
			throw new IllegalStateException();
		}
		/* First we select the ports, later we register it's use so we fail without registering some if we can't reserve all of them */
		Set<Integer> portsNotAvailable = new HashSet<>(); // TODO init with already busy ports
		Map<TunnelRequestTcpItem, Integer> tcpPortMap = new LinkedHashMap<>();
		for (TunnelRequestItem i: new ArrayList<>(request.getItems())) {
			if (i instanceof TunnelRequestTcpItem) {
				int listenPort = chooseTcpListenPort(request, i, portsNotAvailable);
				tcpPortMap.put((TunnelRequestTcpItem) i, listenPort);
				portsNotAvailable.add(listenPort);
			}
		}
		log.info("Selected tcp ports {} for session {}", tcpPortMap.values(), request.getUuid());
		
		tunnel = new JoatseTunnel(owner, request, webListenerConfiguration.getPublicHostname());
		for (TunnelRequestTcpItem r: tcpPortMap.keySet()) {
			int port = tcpPortMap.get(r);
			tunnel.addTcpItem(port, r);
		}
		List<TunnelRequestHttpItem> httpItems = request.getItems().stream()
				.filter(i -> (i instanceof TunnelRequestHttpItem)).map(i->(TunnelRequestHttpItem)i).collect(Collectors.toList());

		Map<String, Set<Integer>> httpPortsNotAvailable = new HashMap<>(); // TODO init with already busy ports
		Collection<InetAddress> allowedAddress = tunnel.getAllowedAddress();
		for (TunnelRequestHttpItem r : httpItems) {
			InetSocketAddress httpHostPort = tunnelRegistry.findAvailableHttpPort(allowedAddress,
					httpPortsNotAvailable);
			if (httpHostPort != null) {
				HttpProxy.HttpTunnel item = httpProxy.newHttpTunnel(tunnel, r, httpHostPort.getHostString(),
						httpHostPort.getPort());
				tunnel.addHttpItem(item);
				httpPortsNotAvailable.computeIfAbsent(httpHostPort.getHostString(), k -> new HashSet<>())
						.add(httpHostPort.getPort());
			} else {
				throw new RuntimeException("There is not an available port for http(s) tunnel");
			}
		}
		log.info(
				"Selected http endpoints {} for session {}", tunnel.getHttpItems().stream()
						.map(h -> h.getCloudHostname() + ":" + h.getListenPort()).collect(Collectors.toList()),
				request.getUuid());
		tunnelRegistry.registerTunnel(tunnel);
		return tunnel;
	}

	/**
	 * get Connection Request
	 */
	public TunnelRequest getTunnelRequest(UUID uuid) {
		lock.writeLock().lock();
		try {
			return tunnelRequestMap.get(uuid);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private int chooseTcpListenPort(TunnelRequest request, TunnelRequestItem i, Set<Integer> portsNotAvailable) {
		// TODO maybe the request wants a specific port
		lock.readLock().lock();
		try {
			ArrayList<Integer> options = new ArrayList<Integer>(openPortMap.keySet());
			int size = options.size();
			int r = new Random().nextInt(size);
			for (int x = 0; x < size; x++) {
				Integer port = options.get((x + r) % size);
				if (!portsNotAvailable.contains(port)) {
					return port;
				}
			}
			throw new RuntimeException("There are not free tcp ports left to choose");
		} finally {
			lock.readLock().unlock();
		}
	}

	public void rejectConnectionRequest(UUID uuid) {
		TunnelRequest request;
		lock.writeLock().lock();
		try {
			request = tunnelRequestMap.remove(uuid);
		} finally {
			lock.writeLock().unlock();
		}
		if (request != null) {
			request.future.complete(new TunnelCreationResult.Rejected(request, "Who knows")); // Out of lock
		}
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		lock.writeLock().lock();
		try {
			setupPortListen();
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void setupPortListen() throws UnknownHostException {
		for (int p = openPortRange.min(); p <= openPortRange.max(); p++) {
			setupPortListen(p);
		}
	}

	private void setupPortListen(int port) throws UnknownHostException {
		
		AsyncTcpPortListener<Void> asyncTcpPortListener = new AsyncTcpPortListener<Void>(
				InetAddress.getByName(listenHostname), port, null,
				(Consumer<Event<Void>>) new Consumer<AsyncTcpPortListener.Event<Void>>() {
					@Override
					public void accept(Event<Void> t) {
						if (t.error != null) {
							log.error(String.format("Could not accept connections on tcp port %s: %s", port, t.error.toString()), t.error);
							lock.writeLock().lock();
							try {
								openPortMap.remove(port);
								/*
								 * TODO Tell someone.
								 * 
								 * We have to detect who's this connection for. Remember there can be several
								 * connections from different users on the same port.
								 */
							} finally {
								lock.writeLock().unlock();
							}
						} else if (t.channel != null) {
							try {
								InetSocketAddress remoteAddress = (InetSocketAddress) IOTools
										.runUnchecked(() -> t.channel.getRemoteAddress());
								lock.readLock().lock();
								try {
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
								} finally {
									lock.readLock().unlock();
								}
							} catch (Exception e) {
								log.warn("Error handling incomming socket: {}", e, e);
								IOTools.closeChannel(t.channel);
							}
						} else {
							log.info("Closed tcp port {}", port);
							lock.writeLock().lock();
							try {
								openPortMap.remove(port);
							} finally {
								lock.writeLock().unlock();
							}
						}
					}
				});
		lock.writeLock().lock();
		try {
			openPortMap.put(port, asyncTcpPortListener);
		} finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public void destroy() throws Exception {
		lock.writeLock().lock();
		try {
			shuttingDown.set(true);
			for (AsyncTcpPortListener<Void> port : this.openPortMap.values()) {
				ForkJoinPool.commonPool().execute(()->port.close()); // Without lock
			}
			this.openPortMap.clear();
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void removeTunnel(UUID uuid) {
		tunnelRegistry.removeTunnel(uuid);
	}
}

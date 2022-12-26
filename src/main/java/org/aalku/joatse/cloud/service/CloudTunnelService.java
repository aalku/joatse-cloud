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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.service.HttpProxy.HttpTunnel;
import org.aalku.joatse.cloud.service.user.repository.UserRepository;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.tools.io.PortRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
		public final boolean unsafe; // Allow unsafe https
		public TunnelRequestHttpItem(long targetId, String targetDescription, URL targetUrl, boolean unsafe) {
			super(targetId, targetDescription, targetUrl.getHost(),
					Optional.of(targetUrl.getPort()).map(p -> p <= 0 ? targetUrl.getDefaultPort() : p).get());
			this.targetUrl = targetUrl;
			this.unsafe = unsafe;
		}
	}

	public class TunnelRequest {
		private final UUID uuid = UUID.randomUUID();
		private final InetSocketAddress requesterAddress;
		private final Collection<TunnelRequestItem> items;
		private final Instant creationTime;
		public final CompletableFuture<TunnelCreationResult> future = new CompletableFuture<>();
		private Collection<InetAddress> allowedAddresses;

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

		public void setAllowedAddresses(Collection<InetAddress> allowedAddress) {
			this.allowedAddresses = allowedAddress;
		}

		public UUID getUuid() {
			return uuid;
		}

		public Collection<InetAddress> getAllowedAddresses() {
			return allowedAddresses;
		}

		public Collection<TunnelRequestItem> getItems() {
			return items;
		}

	}

	public static class JoatseTunnel {

		public class TcpTunnel {
			public class ListenAddress {
				private final int listenPort;
				public ListenAddress(int listenPort) {
					this.listenPort = listenPort;
				}
			}
			private final Map<InetAddress, TcpTunnel.ListenAddress> listenAddresses = new LinkedHashMap<>();
			/**
			 * Random target port id, to id target tuple [host, port]
			 */
			public final long targetId;
			public final String targetDescription;
			public final String targetHostname;
			public final int targetPort;
			public TcpTunnel(TunnelRequestTcpItem req) {
				this.targetId = req.targetId;
				this.targetDescription = req.targetDescription;
				this.targetHostname = req.targetHostname;
				this.targetPort = req.targetPort;
			}
			public JoatseTunnel getTunnel() {
				return JoatseTunnel.this;
			}
			public synchronized boolean matches(InetAddress remoteAddress, int serverPort) {
				return Optional.ofNullable(listenAddresses.get(remoteAddress)).filter(x -> x.listenPort == serverPort)
						.isPresent();
			}
			public synchronized void registerListenAllowedAddress(InetAddress allowedAddress, int serverPort) {
				if (listenAddresses.containsKey(allowedAddress)) {
					throw new IllegalStateException("You can allow each address only once");
				} else {
					listenAddresses.put(allowedAddress, new ListenAddress(serverPort));
				}
			}
			public synchronized void unregisterListenAllowedAddress(InetAddress allowedAddress) {
				if (listenAddresses.remove(allowedAddress) != null) {
					throw new IllegalStateException("You can only remove allowed addresses");
				}
			}
			public synchronized Map<InetAddress, Integer> getListenAddressess() {
				return listenAddresses.entrySet().stream()
						.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().listenPort));
			}
		}

		private final JoatseUser owner;
		private final UUID uuid;
		private final String cloudPublicHostname;
		private Collection<InetAddress> allowedAddresses;
		private final InetSocketAddress requesterAddress;
		private final Instant creationTime;
		/**
		 * Handler for tcp or http(s) connections that are handled as tcp at this point
		 */
		private BiConsumer<Long, AsynchronousSocketChannel> tcpConnectionListener;
		private Collection<TcpTunnel> tcpItems = new ArrayList<>(1);
		private Collection<HttpTunnel> httpItems = new ArrayList<>(1);

		public JoatseTunnel(JoatseUser owner, TunnelRequest request, String cloudPublicHostname) {
			this.owner = owner;
			this.uuid = request.getUuid();
			this.cloudPublicHostname = cloudPublicHostname;
			this.requesterAddress = request.requesterAddress;
			this.allowedAddresses = request.getAllowedAddresses();
			this.creationTime = request.getCreationTime();
		}

		public void setTcpConnectionConsumer(BiConsumer<Long, AsynchronousSocketChannel> tcpConnectionListener) {
			this.tcpConnectionListener = tcpConnectionListener;
		}

		public Collection<InetAddress> getAllowedAddresses() {
			return allowedAddresses;
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

		public TcpTunnel addTcpItem(TunnelRequestTcpItem r) {
			TcpTunnel i = new TcpTunnel(r);
			tcpItems.add(i);
			return i;
		}
		
		public void addHttpItem(HttpTunnel item) {
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

	private Logger log = LoggerFactory.getLogger(CloudTunnelService.class);

	private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

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
	 * Requested connections not confirmed yet
	 */
	Map<UUID, TunnelRequest> tunnelRequestMap = new LinkedHashMap<>();

	/**
	 * For easier testing of target only. FIXME remove this.
	 */
	private String automaticTunnelAcceptance = System.getProperty("automaticTunnelAcceptance");

	@Autowired
	private UserRepository userRepository;

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
						request.setAllowedAddresses(Arrays.asList(InetAddress.getAllByName("localhost"))); // TODO
						acceptTunnelRequest(request.uuid, userRepository.findByLogin(automaticTunnelAcceptance));
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
				JoatseTunnel tunnel = buildAndRegisterTunnel(user, request);
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

	private JoatseTunnel buildAndRegisterTunnel(JoatseUser owner, TunnelRequest request) {
		JoatseTunnel tunnel;
		if (!lock.writeLock().isHeldByCurrentThread()) {
			throw new IllegalStateException();
		}	
		tunnel = new JoatseTunnel(owner, request, webListenerConfiguration.getPublicHostname());
		List<TunnelRequestTcpItem> rItems = request.getItems().stream().filter(x -> x instanceof TunnelRequestTcpItem)
				.map(x -> (TunnelRequestTcpItem) x).collect(Collectors.toList());
		for (TunnelRequestTcpItem r: rItems) {
			tunnel.addTcpItem(r);
		}
		List<TunnelRequestHttpItem> httpItems = request.getItems().stream()
				.filter(i -> (i instanceof TunnelRequestHttpItem)).map(i->(TunnelRequestHttpItem)i).collect(Collectors.toList());

		for (TunnelRequestHttpItem r : httpItems) {
			tunnel.addHttpItem(httpProxy.newHttpTunnel(tunnel, r));
		}
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
	}

	@Override
	public void destroy() throws Exception {
		lock.writeLock().lock();
		try {
			shuttingDown.set(true);
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void removeTunnel(UUID uuid) {
		tunnelRegistry.removeTunnel(uuid);
	}
}

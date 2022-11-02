package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.aalku.joatse.cloud.config.WebListenerConfigurationDetector;
import org.aalku.joatse.cloud.config.WebSocketConfig;
import org.aalku.joatse.cloud.service.user.JoatseUser;
import org.aalku.joatse.cloud.tools.io.IOTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
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

		class TcpItem {
			public final int listenPort;
			/**
			 * Random target port id, to id target tuple [host, port]
			 */
			public final long targetId;
			public final String targetDescription;
			public final String targetHostname;
			public final int targetPort;
			public TcpItem(int listenPort, TunnelRequestTcpItem req) {
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
		private BiConsumer<Long, AsynchronousSocketChannel> tcpConnectionListener;
		private Collection<TcpItem> tcpItems = new ArrayList<CloudTunnelService.JoatseTunnel.TcpItem>(1);

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

		public TcpItem addTcpItem(int port, TunnelRequestTcpItem r) {
			TcpItem i = new TcpItem(port, r);
			tcpItems.add(i);
			return i;
		}

		public TcpItem getTcpItem(long targetId) {
			return getTcpItems().stream().filter(i->i.targetId==targetId).findAny().orElse(null);
		}

		public Collection<TcpItem> getTcpItems() {
			return tcpItems;
		}

		public String getCloudPublicHostname() {
			return cloudPublicHostname;
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

	@Value("${cloud.port.open.range:}")
	private String openPortRangeString;

	private int openPortMin;
	private int openPortMax;

	@Value("${server.hostname.listen:0.0.0.0}")
	private String listenHostname;

	@Autowired
	private WebListenerConfigurationDetector webListenerConfiguration;

	private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

	/**
	 * Map of open server sockets. Remember there can be several connections from
	 * different users on the same port.
	 */
	private Map<Integer, AsynchronousServerSocketChannel> openPortMap = new LinkedHashMap<>();

	/**
	 * Requested connections not confirmed yet
	 */
	Map<UUID, TunnelRequest> tunnelRequestMap = new LinkedHashMap<>();

	Map<UUID, JoatseTunnel> tunnelMap = new LinkedHashMap<>();

	Map<Integer, List<JoatseTunnel.TcpItem>> tcpTunnelsByPortMap = new LinkedHashMap<>();

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
	public TunnelCreationResponse requestTunnel(Principal principal, InetSocketAddress connectionRequesterAddress, Collection<TunnelRequestTcpItem> tcpTunnels) {
		lock.writeLock().lock();
		TunnelRequest request;
		try {
			Collection<TunnelRequestItem> items = new ArrayList<CloudTunnelService.TunnelRequestItem>();
			items.addAll(tcpTunnels);
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

	public void removeTunnel(UUID uuid) {
		lock.writeLock().lock();
		try {
			JoatseTunnel td = tunnelMap.remove(uuid);
			if (td != null) {
				for (JoatseTunnel.TcpItem i: td.getTcpItems()) {
					tcpTunnelsByPortMap.get(i.listenPort).remove(i);
				}
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	/**
	 * The user accepted this connection and completed it with any needed
	 * parameters.
	 */
	public void acceptTunnelRequest(UUID uuid, JoatseUser user) {
		log.info("JoatseUser accepted tunnel request: {}", uuid);
		TunnelRequest request;
		lock.writeLock().lock();
		TunnelCreationResult.Accepted newTunnel;
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
			JoatseTunnel.TcpItem item = tunnel.addTcpItem(port, r);
			tcpTunnelsByPortMap.computeIfAbsent(port, x->new ArrayList<>()).add(item);
		}
		tunnelMap.put(request.uuid, tunnel);
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

	public JoatseTunnel getTunnel(UUID uuid) {
		lock.readLock().lock();
		try {
			JoatseTunnel tunnelDefinition = this.tunnelMap.get(uuid);
			return tunnelDefinition;
		} finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		lock.writeLock().lock();
		try {
			setupPortRange();
			setupPortListen();
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void setupPortListen() {
		for (int p = openPortMin; p <= openPortMax; p++) {
			setupPortListen(p);
		}
	}

	private void setupPortListen(int p) {
		lock.writeLock().lock();
		try {
			AsynchronousServerSocketChannel ass;
			try {
				ass = AsynchronousServerSocketChannel.open();
			} catch (IOException e) {
				throw new RuntimeException(e); // Unexpected
			}
			try {
				ass.bind(new InetSocketAddress(listenHostname, p));
			} catch (IOException e) {
				log.error("Could not listen on port {}: {}", p, e.toString());
				return; // Expected
			}
			lock.writeLock().unlock(); // Temporary unlock
			try {
				registerAcceptCycle(p, ass);
			} finally {
				lock.writeLock().lock();
			}
			openPortMap.put(p, ass);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void registerAcceptCycle(int port, AsynchronousServerSocketChannel ass) {
		lock.writeLock().lock();
		try {
			CompletionHandler<AsynchronousSocketChannel, Void> handler = new CompletionHandler<AsynchronousSocketChannel, Void>() {
				@Override
				public void failed(Throwable e, Void attachment) {
					if (!shuttingDown.get()) {
						log.error("Could not accept connections on port {}: {}", port, e.toString(), e);
						lock.writeLock().lock();
						try {
							try {
								ass.close();
							} catch (IOException e1) {
							}
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
					}
				}

				@Override
				public void completed(AsynchronousSocketChannel channel, Void attachment) {
					try {
						registerAcceptCycle(port, ass); // Accept next. This must be the first line.
						InetSocketAddress remoteAddress = (InetSocketAddress) IOTools
								.runUnchecked(() -> channel.getRemoteAddress());
						lock.readLock().lock();
						try {
							/*
							 * We have to detect who's this connection for. Remember there can be several
							 * connections from different users on the same port.
							 */
							List<JoatseTunnel.TcpItem> portTunnels = tcpTunnelsByPortMap.getOrDefault(port,
									Collections.emptyList());
							for (JoatseTunnel.TcpItem i : portTunnels) {
								JoatseTunnel tunnel = i.getTunnel();
								BiConsumer<Long, AsynchronousSocketChannel> listener = tunnel.tcpConnectionListener;
								if (tunnel.allowedAddress.contains(remoteAddress.getAddress())) {
									log.info("Accepted connection from {}.{} on port {}", remoteAddress, i.targetId, port);
									lock.readLock().unlock(); // Temporary unlock to call callback
									try {
										listener.accept(i.targetId, channel);
									} finally {
										lock.readLock().lock();
									}
									return;
								}
							}
							// Not accepted then rejected
							log.warn("Rejected connection from {} at port {}", remoteAddress, port);
							IOTools.closeChannel(channel);
						} finally {
							lock.readLock().unlock();
						}
					} catch (Exception e) {
						log.warn("Error handling incomming socket: {}", e, e);
						IOTools.closeChannel(channel);
					}
				}
			};
			ass.accept(null, handler);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void setupPortRange() throws Exception {
		if (openPortRangeString == null || openPortRangeString.isBlank()) {
			throw new Exception("You must configure 'cloud.port.open.range' property.");
		} else {
			Matcher m = Pattern.compile("^([1-9][0-9]*)-([1-9][0-9]*)$").matcher(openPortRangeString);
			if (!m.matches()) {
				throw new Exception(
						"You must configure 'cloud.port.open.range' as a port range 'min-max'. For example: '10000-10500'.");
			}
			openPortMin = Integer.parseInt(m.group(1));
			openPortMax = Integer.parseInt(m.group(2));
			if (openPortMax > 65535) {
				openPortMax = 65535;
			}
			if (openPortMin > openPortMax) {
				throw new Exception(
						"You must configure 'cloud.port.open.range' as a port range 'min-max'. Min must be less or equal than max and both must be at most 65535.");
			}
			if (openPortMin < 1024) {
				log.warn(
						"Your 'cloud.port.open.range' enters the privileged port zone. Maybe you should use ports over 1023.");
			}
			if (webListenerConfiguration.getServerPort() >= openPortMin
					&& webListenerConfiguration.getServerPort() <= openPortMax) {
				throw new Exception(
						"You must configure 'server.port' port outside the 'cloud.port.open.range' port range ("
								+ openPortRangeString + ").");
			}
			openPortRangeString = openPortMin + "-" + openPortMax;
			log.info("cloud.port.open.range = {}", openPortRangeString);
		}
	}

	@Override
	public void destroy() throws Exception {
		lock.writeLock().lock();
		try {
			shuttingDown.set(true);
			for (AsynchronousServerSocketChannel ass : this.openPortMap.values()) {
				ass.close();
			}
			this.openPortMap.clear();
		} finally {
			lock.writeLock().unlock();
		}
	}
}

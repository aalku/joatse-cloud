package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 * Handles tunnel definitions and listening ports, notifying the requester if
 * there is a match between a new tcp connection and a defined tunnel.
 */
@Service
public class CloudTunnelService implements InitializingBean, DisposableBean {

	private static class ConnectionInstanceProviderImpl implements ConnectionInstanceProvider {
		
		private List<AsynchronousSocketChannel> queue = null;
		private Consumer<AsynchronousSocketChannel> callback = null;
		
		@Override
		public synchronized void setCallback(Consumer<AsynchronousSocketChannel> callback) {
			this.callback = callback;
			if (queue != null) {
				for (AsynchronousSocketChannel asc: queue) {
					callback.accept(asc);
				}
				this.queue = null;
			}
		}

		public synchronized void acceptedConnection(AsynchronousSocketChannel asc) {
			if (callback == null) {
				if (queue == null) {
					queue = new ArrayList<>();
				}
				queue.add(asc);
			} else {
				callback.accept(asc);
			}
		}
		
	}

	// TODO we need a scheduled task to cleanup tunnelRequestMap from old unconfirmed requests

	public class TunnelRequest {		
		private UUID uuid = UUID.randomUUID();
		private InetSocketAddress requesterAddress;
		private String allowedAddress;
		private String targetHostId;
		public String targetHostname;
		private int targetPort;
		private String targetPortDescription;
		private Instant creationTime;
		public CompletableFuture<TunnelCreationResult> future = new CompletableFuture<>();
		
		public TunnelRequest(InetSocketAddress connectionRequesterAddress, String targetHostname, String targetHostId,
				int targetPort, String targetPortDescription) {
			this.requesterAddress = connectionRequesterAddress;
			this.targetHostId = targetHostId;
			this.targetHostname = targetHostname;
			this.targetPort = targetPort;
			this.targetPortDescription = targetPortDescription;
			this.creationTime = Instant.now();
		}

		public InetSocketAddress getConnectionRequesterAddress() {
			return requesterAddress;
		}

		public String getTargetHostId() {
			return targetHostId;
		}

		public int getTargetPort() {
			return targetPort;
		}

		public String getTargetPortDescription() {
			return targetPortDescription;
		}

		public Instant getCreationTime() {
			return creationTime;
		}

		public CompletableFuture<TunnelCreationResult> getFuture() {
			return future;
		}

		public void setAllowedAddress(String allowedAddress) {
			this.allowedAddress = allowedAddress;
		}

		public UUID getUuid() {
			return uuid;
		}
		
		public String getAllowedAddress() {
			return allowedAddress;
		}
		
	}
	
	public static class TunnelDefinition {
		private final JoatseUser owner;
		private final UUID uuid;
		private final int cloudListenPort;
		private String allowedAddress;
		private final InetSocketAddress requesterAddress;
		private final String targetHostId;
		private final String targetHostname;
		private final int targetPort;
		private final String targetPortDescription;
		private final Instant creationTime;
		private Consumer<AsynchronousSocketChannel> connectionConsumer;

		public TunnelDefinition(JoatseUser owner, UUID uuid, int cloudListenPort, InetSocketAddress requesterAddress,
				String allowedAddress, String targetHostId, String targetHostname, int targetPort,
				String targetPortDescription, Instant creationTime) {
			this.owner = owner;
			this.uuid = uuid;
			this.cloudListenPort = cloudListenPort;
			this.requesterAddress = requesterAddress;
			this.allowedAddress = allowedAddress;
			this.targetHostId = targetHostId;
			this.targetHostname = targetHostname;
			this.targetPort = targetPort;
			this.targetPortDescription = targetPortDescription;
			this.creationTime = creationTime;
		}
		
		public void setConnectionConsumer(Consumer<AsynchronousSocketChannel> connectionConsumer) {
			this.connectionConsumer = connectionConsumer;
		}
		public String getAllowedAddress() {
			return allowedAddress;
		}
		public InetSocketAddress getRequesterAddress() {
			return requesterAddress;
		}
		public UUID getUuid() {
			return uuid;
		}
		public int getCloudListenPort() {
			return cloudListenPort;
		}
		public String getTargetHostId() {
			return targetHostId;
		}
		public int getTargetPort() {
			return targetPort;
		}
		public String getTargetPortDescription() {
			return targetPortDescription;
		}
		public Instant getCreationTime() {
			return creationTime;
		}
		public Consumer<AsynchronousSocketChannel> getConnectionConsumer() {
			return connectionConsumer;
		}

		public JoatseUser getOwner() {
			return owner;
		}

		public String getTargetHostname() {
			return targetHostname;
		}
	}


	public static class TunnelCreationResponse {
		/** Future result of the petition of connection creation */
		CompletableFuture<TunnelCreationResult> result;
		/** Url to accept the connection petition (and take note of and confirm other connection adjustments */
		String confirmationUri;
		/** IF of this connection petition/config */
		UUID uuid;
		public TunnelCreationResponse(UUID uuid, String confirmationUri, CompletableFuture<TunnelCreationResult> result) {
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
		private final int targetPort;
	
		public TunnelCreationResult(TunnelRequest request) {
			this.uuid = request.uuid;
			this.targetPort = request.targetPort;
		}

		public abstract boolean isAccepted();
	
		public UUID getUuid() {
			return uuid;
		}
		
		public int getTargetPort() {
			return targetPort;
		}


		public static class Accepted extends TunnelCreationResult {
			private final ConnectionInstanceProvider connectionInstanceListener;
			private InetSocketAddress publicAddress;

			public Accepted(TunnelRequest request,
					ConnectionInstanceProviderImpl connectionInstanceProvider,
					InetSocketAddress publicAddress) {
				super(request);
				this.connectionInstanceListener = connectionInstanceProvider;
				this.publicAddress = publicAddress;
			}
			public boolean isAccepted() {
				return true;
			}
			public ConnectionInstanceProvider getConnectionInstanceListener() {
				return connectionInstanceListener;
			}
			public InetSocketAddress getPublicAddress() {
				return publicAddress;
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
	
	Map<UUID, TunnelDefinition> tunnelMap = new LinkedHashMap<>();

	Map<Integer, List<TunnelDefinition>> tunnelsByPortMap = new LinkedHashMap<>();

	/**
	 * For easier testing of target only. FIXME remove this.
	 */
	private boolean automaticTunnelAcceptance = Boolean.getBoolean("automaticTunnelAcceptance");

	/**
	 * Requests a connection need. The client user must then confirm it from the ip
	 * address or class from where they want to connect to it.
	 * 
	 * @param principal
	 * @param inetSocketAddress
	 * @param targetHostId
	 * @param targetPort
	 * @param targetPortDescription
	 * @return
	 */
	public TunnelCreationResponse requestTunnel(Principal principal,
			InetSocketAddress connectionRequesterAddress, String targetHostId, String targetHostname, int targetPort, String targetPortDescription) {
		lock.writeLock().lock();
		TunnelRequest request;
		try {
			/*
			 * Remember there can be several connections from different users on the same
			 * port.
			 */
			request = new TunnelRequest(connectionRequesterAddress, targetHostId, targetHostname, targetPort, targetPortDescription);
			tunnelRequestMap.put(request.uuid, request);
		} finally {
			lock.writeLock().unlock();
		}
		if (automaticTunnelAcceptance) { // TODO remove this mock
			new Thread() {
				public void run() {
					try {
						Thread.sleep(500);
						acceptTunnelRequest(request.uuid, null);
					} catch (InterruptedException e) {
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
			TunnelDefinition td = tunnelMap.remove(uuid);
			if (td != null) {
				tunnelsByPortMap.get(td.cloudListenPort).remove(td);
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	/**
	 * The user accepted this connection and completed it with any needed parameters. 
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
			int port = chooseListenPort(request);
			log.info("Selected port {} for session {}", port, uuid);

			// Connect listeners
			TunnelDefinition acceptedTunnel = registerTunnel(user, request, port);
			ConnectionInstanceProviderImpl connectionInstanceProvider = new ConnectionInstanceProviderImpl();
			acceptedTunnel.setConnectionConsumer((AsynchronousSocketChannel asc)->{ // Can be executed with lock. It does not run callback
				connectionInstanceProvider.acceptedConnection(asc);
			});
			newTunnel = new TunnelCreationResult.Accepted(request, connectionInstanceProvider,
					InetSocketAddress.createUnresolved(webListenerConfiguration.getPublicHostname(), port));
		} finally {
			lock.writeLock().unlock();
		}
		request.future.complete(newTunnel); // Without lock
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

	private TunnelDefinition registerTunnel(JoatseUser owner, TunnelRequest request, int port) {
		TunnelDefinition acceptedConnection;
		lock.writeLock().lock();
		try {
			acceptedConnection = new TunnelDefinition(owner, request.uuid, port, request.requesterAddress,
					request.allowedAddress, request.targetHostId, request.targetHostname, request.targetPort,
					request.targetPortDescription, request.creationTime);
			tunnelMap.put(request.uuid, acceptedConnection);
			tunnelsByPortMap.computeIfAbsent(port, p->new ArrayList<>()).add(acceptedConnection);
		} finally {
			lock.writeLock().unlock();
		}
		return acceptedConnection;
	}
	
	private int chooseListenPort(TunnelRequest request) {
		// TODO maybe the request wants a specific port
		lock.readLock().lock();
		try {
			ArrayList<Integer> options = new ArrayList<Integer>(openPortMap.keySet());
			Integer port = options.get(new Random().nextInt(options.size()));
			return port;
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
	
	public TunnelDefinition getTunnel(UUID uuid) {
		lock.readLock().lock();
		try {
			TunnelDefinition tunnelDefinition = this.tunnelMap.get(uuid);
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
						InetSocketAddress remoteAddress = (InetSocketAddress) IOTools.runUnchecked(() -> channel.getRemoteAddress());
						lock.readLock().lock();
						try {
							/*
							 * We have to detect who's this connection for. Remember there can be several
							 * connections from different users on the same port.
							 */
							List<TunnelDefinition> portTunnels = tunnelsByPortMap.getOrDefault(port, Collections.emptyList());
							for (TunnelDefinition t: portTunnels) {
								if (InetAddress.getByName(t.allowedAddress).equals(remoteAddress.getAddress())) {
									log.info("Accepted connection from {} on port {}", remoteAddress, port);
									lock.readLock().unlock(); // Temporary unlock to call callback
									try {
										t.connectionConsumer.accept(channel);
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
			if (webListenerConfiguration.getServerPort() >= openPortMin && webListenerConfiguration.getServerPort() <= openPortMax) {
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

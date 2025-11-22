package org.aalku.joatse.cloud.service.sharing;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.nio.channels.AsynchronousSocketChannel;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.config.SecurityConfigurationProperties;
import org.aalku.joatse.cloud.service.sharing.command.CommandTunnel;
import org.aalku.joatse.cloud.service.sharing.file.FileTunnel;
import org.aalku.joatse.cloud.service.sharing.http.HttpEndpointGenerator;
import org.aalku.joatse.cloud.service.sharing.http.HttpTunnel;
import org.aalku.joatse.cloud.service.sharing.request.LotSharingRequest;
import org.aalku.joatse.cloud.service.sharing.request.TunnelRequestItem;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.service.sharing.shared.TcpTunnel;
import org.aalku.joatse.cloud.service.user.repository.PreconfirmedSharesRepository;
import org.aalku.joatse.cloud.service.user.repository.UserRepository;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.service.user.vo.PreconfirmedShare;
import org.aalku.joatse.cloud.tools.io.IOTools;
import org.aalku.joatse.cloud.tools.net.AddressRange;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Handles tunnel requests, accepted definitions, tunnel connections and
 * listening ports.
 * 
 * This service also notifies the requester if there is a match between a new
 * tcp connection and a defined tunnel.
 * 
 * A JWSSession can be mapped to nothing or a LotSharingRequest or a SharedResourceLot.
 */
@Service
public class SharingManager implements InitializingBean, DisposableBean {

	// TODO we need a scheduled task to cleanup tunnelRequestMap from old
	// unconfirmed requests

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

		public CompletableFuture<TunnelCreationResult> getResult() {
			return result;
		}

		public String getConfirmationUri() {
			return confirmationUri;
		}
	}

	public interface ConnectionInstanceProvider {
		void setCallback(Consumer<AsynchronousSocketChannel> callback);
	}

	public abstract static class TunnelCreationResult {
		private final UUID uuid;
		private final Collection<TunnelRequestItem> items;

		public TunnelCreationResult(LotSharingRequest request) {
			this.uuid = request.getUuid();
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
			private SharedResourceLot tunnel;

			public Accepted(LotSharingRequest request, SharedResourceLot tunnel) {
				super(request);
				this.tunnel = tunnel;
			}

			public boolean isAccepted() {
				return true;
			}

			public SharedResourceLot getTunnel() {
				return tunnel;
			}
		};

		public static class Rejected extends TunnelCreationResult {
			private final String cause;

			public Rejected(LotSharingRequest request, String cause) {
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

	private Logger log = LoggerFactory.getLogger(SharingManager.class);

	private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	@Autowired
	private ListenerConfigurationDetector webListenerConfiguration;

	@Autowired
	private TunnelRegistry tunnelRegistry;

	private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

	/**
	 * Requested connections not confirmed yet
	 */
	Map<UUID, LotSharingRequest> tunnelRequestMap = new LinkedHashMap<>();



	@Autowired
	private UserRepository userRepository;

	private Collection<Integer> tcpOpenPorts;

	@Autowired
	private HttpEndpointGenerator httpEndpointGenerator;
	
	@Autowired
	private PreconfirmedSharesRepository preconfirmedSharesRepository;

	@Autowired
	private SecurityConfigurationProperties securityConfig;

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
	public TunnelCreationResponse requestTunnel(Principal principal, LotSharingRequest request) {
		lock.writeLock().lock();
		try {
			tunnelRequestMap.put(request.getUuid(), request);
		} finally {
			lock.writeLock().unlock();
		}
		if (request.getPreconfirmedUuid() != null) {
			Optional<PreconfirmedShare> preconfirmedShareOptional = preconfirmedSharesRepository
					.findById(request.getPreconfirmedUuid());
			if (preconfirmedShareOptional.isPresent()) {
				PreconfirmedShare saved = preconfirmedShareOptional.get();
				
				// Copy allowed address patterns from preconfirmed share to request BEFORE validation
				if (securityConfig.isUnsafeStaticRemoteAddressAuthorization() && 
					saved.getAllowedAddressPatterns() != null && !saved.getAllowedAddressPatterns().isEmpty()) {
					// TODO: In the future, the allowedAddresses should come from the joatse-target request and be validated against the preconfirmed patterns. Currently it's not supported for them to come from the joatse-target process, but it should be.
					request.setAllowedAddressPatterns(saved.getAllowedAddressPatterns());
				} else {
					// Security: Clear allowedAddresses when not using static authorization
					request.setAllowedAddressPatterns(Collections.emptyList());
				}
				
				// check that they are the same requested resources or less				
				String preconfirmationError = checkPreconfirmation(saved, request);
				if (preconfirmationError == null) {
					acceptTunnelRequest(request.getUuid(), saved.getOwner());
				} else {
					rejectConnectionRequest(request.getUuid(), "Asked for resources that were not preconfirmed: " + preconfirmationError);
				}
			} else {
				rejectConnectionRequest(request.getUuid(), "Invalid preconfirmation");
			}
			return new TunnelCreationResponse(request.getUuid(), null, request.getFuture());
		}
		return new TunnelCreationResponse(request.getUuid(), buildConfirmationUri(request), request.getFuture());
	}

	private String checkPreconfirmation(PreconfirmedShare saved, LotSharingRequest request) {
		Collection<TunnelRequestItem> items;
		try {
			items = LotSharingRequest.fromJsonSharedResources(new JSONObject(saved.getResources()));
		} catch (MalformedURLException | JSONException e) {
			log.warn("Error checking preconfirmation: " + e, e);
			return "Error checking preconfirmation";
		}
		// Check if requester's IP address is allowed by the saved address patterns
		Set<String> savedPatterns = saved.getAllowedAddressPatterns();
		if (savedPatterns != null && !savedPatterns.isEmpty()) {
			InetAddress requesterAddr = request.getRequesterAddress().getAddress();
			boolean isAllowed = savedPatterns.stream()
				.map(pattern -> {
					try {
						return AddressRange.of(pattern);
					} catch (IllegalArgumentException e) {
						log.warn("Invalid saved address pattern '{}': {}", pattern, e.getMessage());
						return null;
					}
				})
				.filter(Objects::nonNull)
				.anyMatch(range -> range.matches(requesterAddr));
			
			if (!isAllowed) {
				log.warn("Requester address {} not permitted by preconfirmed patterns: {}", requesterAddr, savedPatterns);
				return "AllowedAddress mistmatch";
			}
		}
		for (final TunnelRequestItem ir : Optional.ofNullable(request.getItems()).orElse(Collections.emptyList())) {
			if (!items.stream().filter(is->is.equals(ir)).findAny().isPresent()) {
				log.warn("Can't find match for " + ir + ", saved items were " + items);
				return "New request item: " + ir;
			}
		}
		if (request.isAutoAuthorizeByHttpUrl() && !saved.isAutoAuthorizeByHttpUrl()) {
			return "new requisite: autoAuthorizeByHttpUrl";
		}
		return null;
	}

	private String buildConfirmationUri(LotSharingRequest request) {
		UriComponentsBuilder ub = UriComponentsBuilder.newInstance();
		ub.scheme(webListenerConfiguration.isSslEnabled() ? "https" : "http");
		ub.host(webListenerConfiguration.getPublicHostname());
		ub.port(webListenerConfiguration.getServerPort());
		ub.path("/CF");
		return ub.toUriString() + "?ts=" + System.currentTimeMillis() + "#" + request.getUuid();
	}

	/**
	 * The user accepted this connection and completed it with any needed
	 * parameters.
	 */
	public void acceptTunnelRequest(UUID uuid, JoatseUser user) {
		log.info("JoatseUser accepted tunnel request: {}", uuid);
		LotSharingRequest request = null;
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
				SharedResourceLot tunnel = buildAndRegisterTunnel(user, request);
				newTunnel = new TunnelCreationResult.Accepted(request, tunnel);
			} finally {
				lock.writeLock().unlock();
			}
			request.getFuture().complete(newTunnel); // Without lock
		} catch (Exception e) {
			if (request != null) {
				// Reject
				request.getFuture().completeExceptionally(e);
				return;
			} else {
				throw e;
			}
		}
	}

	private SharedResourceLot buildAndRegisterTunnel(JoatseUser owner, LotSharingRequest request) {
		if (!lock.writeLock().isHeldByCurrentThread()) {
			throw new IllegalStateException();
		}
		SharedResourceLot tunnel = new SharedResourceLot(owner, request, webListenerConfiguration.getPublicHostname());
		tunnel.selectTcpPorts(tcpOpenPorts);
		tunnel.selectHttpEndpoints(httpEndpointGenerator);
		tunnel.selectFileEndpoints(httpEndpointGenerator);
		tunnelRegistry.registerTunnel(tunnel);
		log.info("Tunnel registered for request {} with {} HTTP, {} file, {} TCP items", 
			request.getUuid(), tunnel.getHttpItems().size(), tunnel.getFileItems().size(), tunnel.getTcpItems().size());
		return tunnel;
	}

	/**
	 * get Connection Request
	 */
	public LotSharingRequest getTunnelRequest(UUID uuid) {
		lock.writeLock().lock();
		try {
			return tunnelRequestMap.get(uuid);
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void rejectConnectionRequest(UUID uuid, String reason) {
		try {
			Thread.sleep(1000); // Safeguard for unintentional DoS
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		LotSharingRequest request;
		lock.writeLock().lock();
		try {
			request = tunnelRequestMap.remove(uuid);
		} finally {
			lock.writeLock().unlock();
		}
		if (request != null) {
			request.getFuture().complete(new TunnelCreationResult.Rejected(request, reason)); // Out of lock
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

	public void setTcpOpenPorts(ArrayList<Integer> ports) {
		this.tcpOpenPorts = ports;
	}

	public void receivedTcpConnection(int port, AsynchronousSocketChannel channel,
			InetSocketAddress remoteAddress) {
		/*
		 * We have to detect who's this connection for. Remember there can be several
		 * connections from different users on the same port.
		 */
		List<TcpTunnel> matching = tunnelRegistry
				.findMatchingTcpTunnel(remoteAddress.getAddress(), port);
		if (matching.size() == 1) {
			TcpTunnel tcpTunnel = matching.get(0);
			SharedResourceLot tunnel = tcpTunnel.getTunnel();
			log.info("Accepted connection from {}.{} on port {}", remoteAddress,
					tcpTunnel.targetId, port);
			tunnel.tunnelTcpConnection(tcpTunnel.targetId, channel);
		} else {
			// Not accepted then rejected
			log.warn("Rejected connection from {} at port {}", remoteAddress, port);
			IOTools.closeChannel(channel);
		}
	}

	/**
	 * A httpClientEnd connection has arrived. Returns a context object to be used
	 * on the next call.
	 */
	public Object switchboardConnected(UUID uuid, long targetId) {
		Object tunnel = tunnelRegistry.getTunnel(uuid, targetId);
		if (tunnel == null) {
			log.warn("Unexpected connection or wrong protocol: {}.{}", uuid, targetId);
			return null;
		} else {
			log.info("Switchboard Received Tunnel connection: {}.{}", uuid, targetId);
			return tunnel;
		}
	}

	/** The httpClientEnd connection is ready */
	public void switchboardConnectionReady(Object context, AsynchronousSocketChannel channel) {
		SharedResourceLot tunnel = null;
		long targetId = -1;
		if (context instanceof HttpTunnel) {
			HttpTunnel httpTarget = (HttpTunnel) context;
			tunnel = httpTarget.getTunnel();
			targetId = httpTarget.getTargetId();
		} else if (context instanceof TcpTunnel) {
			TcpTunnel tcpTarget = (TcpTunnel) context;
			tunnel = tcpTarget.getTunnel();
			targetId = tcpTarget.getTargetId();
		} else {
			throw new RuntimeException();
		}
		tunnel.tunnelTcpConnection(targetId, channel);
		// TODO schedule a periodic check to log the disconnection
	}

	/**
	 * Gets an http tunnel for an http request.
	 * 
	 * HttpProxyManager needs the whole tunnel, it's not like TCP. The communications will
	 * come again to this service through {@link httpClientEndConnected()} and
	 * {@link httpClientEndConnectionReady()}
	 */
	public HttpTunnel getTunnelForHttpRequest(InetAddress remoteAddress, int serverPort, String serverName, String protocol) {
		List<HttpTunnel> res = tunnelRegistry.findMatchingHttpTunnel(remoteAddress, serverPort, serverName, protocol);
		if (res.size() > 0) {
			HttpTunnel ht = res.get(0);
			SharedResourceLot srl = ht.getTunnel();
			/* If not authorized maybe it should be */
			boolean isAlreadyAllowed = srl.getAllowedAddressRanges().stream()
				.anyMatch(range -> range.matches(remoteAddress));
			if (!isAlreadyAllowed && srl.isAutoAuthorizeByHttpUrl()) {
				srl.addAllowedAddressRange(AddressRange.of(remoteAddress.getHostAddress()));
			}
			return ht;
		} else {
			return null;
		}
	}

	public HttpTunnel getHttpTunnelById(UUID uuid, long httpTunnelId) {
		HttpTunnel res = tunnelRegistry.getTunnel(uuid, httpTunnelId);
		return res;
	}

	public CommandTunnel getCommandTunnelById(UUID uuid, long targetId) {
		CommandTunnel res = tunnelRegistry.getTunnel(uuid, targetId);
		return res;
	}

	public FileTunnel getTunnelForFileRequest(InetAddress remoteAddress, int serverPort, String serverName, String protocol, String requestPath) {
		List<FileTunnel> res = tunnelRegistry.findMatchingFileTunnel(remoteAddress, serverPort, serverName, protocol, requestPath);
		log.debug("File tunnel lookup for {}:{}{}{}  returned {} result(s)", serverName, serverPort, protocol, requestPath, res.size());
		if (res.size() > 0) {
			FileTunnel ft = res.get(0);
			SharedResourceLot srl = ft.getSharedResourceLot();
			/* If not authorized maybe it should be */
			boolean isAlreadyAllowed = srl.getAllowedAddressRanges().stream()
				.anyMatch(range -> range.matches(remoteAddress));
			if (!isAlreadyAllowed && srl.isAutoAuthorizeByHttpUrl()) {
				srl.addAllowedAddressRange(AddressRange.of(remoteAddress.getHostAddress()));
			}
			return ft;
		} else {
			return null;
		}
	}

}

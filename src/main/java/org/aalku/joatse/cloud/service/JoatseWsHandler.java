package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.JWSSession.SessionPingHandler;
import org.aalku.joatse.cloud.service.sharing.SharingManager;
import org.aalku.joatse.cloud.service.sharing.SharingManager.TunnelCreationResponse;
import org.aalku.joatse.cloud.service.sharing.SharingManager.TunnelCreationResult;
import org.aalku.joatse.cloud.service.sharing.SharingManager.TunnelCreationResult.Accepted;
import org.aalku.joatse.cloud.service.sharing.command.CommandTunnel;
import org.aalku.joatse.cloud.service.sharing.command.TerminalSessionHandler;
import org.aalku.joatse.cloud.service.sharing.http.HttpTunnel;
import org.aalku.joatse.cloud.service.sharing.request.LotSharingRequest;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.service.sharing.shared.TcpTunnel;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.tools.io.IOTools;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Component
public class JoatseWsHandler extends AbstractWebSocketHandler implements WebSocketHandler, InitializingBean, DisposableBean {
	
	private static final int MESSAGE_SIZE_LIMIT = 1024*64;

	private Logger log = LoggerFactory.getLogger(JoatseWsHandler.class);
	
	@Autowired
	private SharingManager sharingManager;
	
	@Autowired
	private BandwithLimitManager bandwithLimitManager;
	
	/**
	 * Map WebSocketSession.sessionId-->JWSSession
	 */
	private ConcurrentHashMap<String, JWSSession> wsSessionMap = new ConcurrentHashMap<String, JWSSession>();
	
	private Thread pingPongThread;

	static final String SESSION_KEY_CLOSE_REASON = "closeReason";

	private static final long PING_BETWEEN_SECONDS = 20;
	private static final long PING_TIMEOUT_SECONDS = 60;

	private enum State {
		WAITING_COMMAND, RUNNING, CLOSED;
	}
	
	private AtomicReference<State> getStateReference(WebSocketSession session) {
		@SuppressWarnings("unchecked")
		AtomicReference<State> arState = (AtomicReference<State>) session.getAttributes().computeIfAbsent("state",
				k -> new AtomicReference<State>(null));
		return arState;
	}
	
	@Override
	public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
		wsSession.setBinaryMessageSizeLimit(MESSAGE_SIZE_LIMIT);
		HttpHeaders handshakeHeaders = wsSession.getHandshakeHeaders();
		log.info("handshakeHeaders: {} - {}", wsSession.getId(), handshakeHeaders);
		getStateReference(wsSession).set(State.WAITING_COMMAND);
		wsSessionMap.put(wsSession.getId(), new JWSSession(wsSession, bandwithLimitManager));
	}
	
	@Override
	public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) throws Exception {
		log.info("Ws connection closed: {}", wsSession.getId());
		JWSSession jwsSession = wsSessionMap.get(wsSession.getId());
		if (jwsSession != null) {
			try {
				UUID uuid = jwsSession.getTunnelUUID();
				if (uuid != null) {
					log.info("SharedResourceLot closed because WS session was closed: {}", uuid);
					sharingManager.removeTunnel(uuid);
				}
				closeSession(wsSession, "WS got closed", null); // This reason will be saved if it was not already
			} finally {
				wsSessionMap.remove(wsSession.getId(), jwsSession);
			}
		}
		String closeReason = (String) wsSession.getAttributes().getOrDefault(SESSION_KEY_CLOSE_REASON, "Closed from the client side");
		log.info("Ws session was closed. Reason: {}", closeReason);
	}
	
	@Override
	protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
		AtomicReference<State> stateReference = getStateReference(wsSession);
		if (stateReference.get() != State.WAITING_COMMAND) {
			closeSession(wsSession, "Protocol error: Wasn't waiting for text message", null);
			return;
		}
		log.info("handleTextMessage: {} - {}", wsSession.getId(), message.getPayload());
		log.info("session.attributes.before: {} - {}", wsSession.getId(), wsSession.getAttributes());
		try {
			JSONObject js = new JSONObject(message.getPayload());
			String request = js.getString("request");
			if (request.equals("CONNECTION")) {
				
				LotSharingRequest lotSharingRequest = LotSharingRequest.fromJsonRequest(js, wsSession.getRemoteAddress());
				TunnelCreationResponse requestedTunnel = sharingManager.requestTunnel(wsSession.getPrincipal(),
						lotSharingRequest);
				if (!requestedTunnel.getResult().isDone()) {
					// Not done (not rejected nor error) so we ask the user to use the url to confirm the connection
					wsSessionMap.get(wsSession.getId()).sendMessage((WebSocketMessage<?>) new TextMessage(showUserAcceptanceUriMessage(requestedTunnel)));
				}
				requestedTunnel.getResult().whenComplete((r,e)->{
					if (e == null && r != null && r.isAccepted()) {
						Accepted acceptedTunnel = (TunnelCreationResult.Accepted)r;
						SharedResourceLot sharedResourceLot = acceptedTunnel.getTunnel();
						wsSession.getAttributes().put("uuid", sharedResourceLot.getUuid());
						JWSSession jWSSession = wsSessionMap.get(wsSession.getId());
						if (associateSharedResourceLot(jWSSession, sharedResourceLot)) {
							try {
								jWSSession.sendMessage((WebSocketMessage<?>) new TextMessage(runningTunnelMessage(sharedResourceLot))).get();
								getStateReference(wsSession).set(State.RUNNING); // Allow to process connections
							} catch (Exception e1) {
								log.error("Exception sending text response message: {}", e1, e1);
								closeSession(wsSession, "Exception sending text response message", e1);
							}
						} else {
							closeSession(wsSession, "Exception processing channel acceptance", null); // TODO
						}

					} else {
						String rejectionCause;
						if (e != null) {
							rejectionCause = e.getMessage();
							log.error("Rejected connection. Cause: " + e, e);
						} else {
							rejectionCause = ((TunnelCreationResult.Rejected) r).getRejectionCause();
							log.error("Rejected connection. Cause: {}", rejectionCause);
						}
						try {
							wsSessionMap.get(wsSession.getId()).sendMessage((WebSocketMessage<?>) new TextMessage(rejectionJsonMessage(rejectionCause))).get();
						} catch (Exception e1) {
							log.error("Exception sending text response message: {}", e, e);
							closeSession(wsSession, "Exception sending text response message", e);
						}
					}
				});
				return; // Do not close
			} else {
				throw new IllegalStateException();
			}
		} catch (Exception e) {
			log.error("Exception processing text message: " + e, e);
			closeSession(wsSession, "Exception processing text message", e);
			return;
		} finally {
			log.info("session.attributes.after: {} - {}", wsSession.getId(), wsSession.getAttributes());
		}
	}
	
	@Override
	protected void handleBinaryMessage(WebSocketSession wsSession, BinaryMessage message) throws Exception {
		JWSSession jSession = wsSessionMap.get(wsSession.getId());
		try {
			if (jSession == null) {
				throw new IOException("Unexpected binary message before tunnel creation");
			} else {
				jSession.handleBinaryMessage(message);
			}
		} catch (Exception e) {
			log.error("Exception processing binary message: " + e, e);
			closeSession(wsSession, "Exception processing binary message", e);
		}
	}
	
	@Override
	protected void handlePongMessage(WebSocketSession wsSession, PongMessage message) throws Exception {
		JWSSession jSession = wsSessionMap.get(wsSession.getId());
		try {
			if (jSession == null) {
				throw new IOException("Unexpected pong message before tunnel creation");
			} else {
				jSession.getSessionPingHandler().receivedPong();
			}
		} catch (Exception e) {
			log.error("Exception processing pong message: " + e, e);
			closeSession(wsSession, "Exception processing pong message", e);
		}
	}


	private boolean associateSharedResourceLot(JWSSession jWSSession, SharedResourceLot srl) {
		jWSSession.setSharedResourceLot(srl);
		try {
			/*
			 * This is for http(S) too, for the final link between proxied connection and
			 * websocket to target (and from target to http(s) server)
			 */
			srl.setTcpConnectionConsumer(new SessionTunnelTcpConnectionHandler(jWSSession, srl));
			/*
			 * This is for incoming terminal/command connections
			 */
			srl.setTerminalSessionHandler(new TerminalSessionHandler(jWSSession, srl));
			srl.setTargetPublicKeyProvider(()->jWSSession.getTargetPublicKey());
		} catch (Exception e2) {
			log.error("Exception processing channel acceptance: {}", e2, e2);
			return false;
		}
		log.info("SharedResourceLot was succesfully created!!");
		return true;
	}

	private CharSequence runningTunnelMessage(SharedResourceLot tunnel) {
		String cloudPublicHostname = tunnel.getCloudPublicHostname();
		
		JSONObject res = new JSONObject();
		res.put("request", "CONNECTION");
		res.put("response", "RUNNING");
		Collection<JSONObject> tcpTunnels = new ArrayList<>();
		for (TcpTunnel i: tunnel.getTcpItems()) {
			JSONObject j = new JSONObject();
			j.put("listenHost", cloudPublicHostname);
			j.put("listenPort", i.getListenPort());
			j.put("targetHostname", i.targetHostname);
			j.put("targetPort", i.targetPort);
			tcpTunnels.add(j);
		}
		res.put("tcpTunnels", tcpTunnels);

		Collection<JSONObject> httpTunnels = new ArrayList<>();
		for (HttpTunnel i: tunnel.getHttpItems()) {
			JSONObject j = new JSONObject();
			j.put("listenUrl", i.getListenUrl());
			j.put("targetUrl", i.getTargetURL());
			httpTunnels.add(j);
		}
		res.put("httpTunnels", httpTunnels);
		
		Collection<JSONObject> commandTunnels = new ArrayList<>();
		for (CommandTunnel i: tunnel.getCommandItems()) {
			JSONObject j = new JSONObject();
			j.put("targetHostname", i.getTargetHostname());
			j.put("targetPort", i.getTargetPort());
			j.put("command", new JSONArray(Arrays.asList(i.getCommand())));
			commandTunnels.add(j);
		}
		res.put("commandTunnels", commandTunnels);

		return res.toString();
	}

	private CharSequence showUserAcceptanceUriMessage(TunnelCreationResponse requestedConnection) {
		JSONObject res = new JSONObject();
		res.put("request", "CONNECTION");
		res.put("response", "CONFIRM");
		res.put("confirmationUri", requestedConnection.getConfirmationUri());
		return res.toString();
	}

	private String rejectionJsonMessage(String rejectionCause) {
		JSONObject res = new JSONObject();
		res.put("request", "CONNECTION");
		res.put("response", "REJECTED");
		res.put("rejectionCause", rejectionCause);
		return res.toString();
	}

	/** Close session */
	private void closeSession(WebSocketSession wsSession, String reason, Throwable e) {
		synchronized (wsSession) {
			getStateReference(wsSession).set(State.CLOSED);
			Optional.ofNullable(wsSessionMap.get(wsSession.getId()))
					.ifPresent(s -> IOTools.runFailable(() -> s.close(reason, e)));
			IOTools.runFailable(()->wsSession.close());
		}
	}
	
	public Collection<JWSSession> getSessions(JoatseUser owner) {
		return wsSessionMap.values().stream().filter(
				s -> Optional.ofNullable(s.getSharedResourceLot()).map(t -> t.getOwner()).map(o -> o.equals(owner)).orElse(false))
				.collect(Collectors.toList());
	}

	@Override
	public void destroy() throws Exception {
		pingPongThread.interrupt();
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		launchPingPong();
	}

	private void launchPingPong() {
		pingPongThread = new Thread("pingPongThread") {
			@Override
			public void run() {
				while (true) {
					try {
						for (JWSSession s: wsSessionMap.values()) {
							SessionPingHandler ph = s.getSessionPingHandler();
							long lastPingAgoSeconds = ph.getLastPingAgoSeconds();
							if (lastPingAgoSeconds > PING_BETWEEN_SECONDS) {
								long lastPongAgoSeconds = ph.getLastPongAgoSeconds();
								if (lastPongAgoSeconds > lastPingAgoSeconds) {
									// Pong is not answered
									if (lastPingAgoSeconds > PING_TIMEOUT_SECONDS) {
										s.close("Ping timeout after " + lastPingAgoSeconds + " seconds");
									}
								} else {
									ph.sendPing();
								}
							}
						}
					} catch (Exception e) {
						log.warn("Exception in pingPongThread", e);
					}
					try {
						Thread.sleep(1000L);
					} catch (InterruptedException e) {
						break;
					}
				}
			}
		};
		pingPongThread.setDaemon(true);
		pingPongThread.start();
	}

	public void closeSession(SharedResourceLot s, String reason) {
		Optional<JWSSession> wss = wsSessionMap.values().stream().filter(x->x.getSharedResourceLot().getUuid().equals(s.getUuid())).findFirst();
		if (wss.isEmpty()) {
			throw new NoSuchElementException();
		} else {
			wss.get().close(reason);
		}
	}
}

package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.net.URL;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel.TcpTunnel;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelCreationResponse;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelCreationResult;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelCreationResult.Accepted;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelRequestHttpItem;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelRequestTcpItem;
import org.aalku.joatse.cloud.service.HttpProxy.HttpTunnel;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.tools.io.IOTools;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Component
public class JoatseWsHandler extends AbstractWebSocketHandler implements WebSocketHandler {
	
	private static final int MESSAGE_SIZE_LIMIT = 1024*64;

	private Logger log = LoggerFactory.getLogger(JoatseWsHandler.class);
	
	@Autowired
	private CloudTunnelService cloudTunnelService;
	
	/**
	 * Map WebSocketSession.sessionId-->JWSSession
	 */
	private ConcurrentHashMap<String, JWSSession> wsSessionMap = new ConcurrentHashMap<String, JWSSession>();

	static final String SESSION_KEY_CLOSE_REASON = "closeReason";

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
		wsSessionMap.put(wsSession.getId(), new JWSSession(wsSession));
	}
	
	@Override
	public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) throws Exception {
		log.info("Ws connection closed: {}", wsSession.getId());
		JWSSession jwsSession = wsSessionMap.remove(wsSession.getId());
		if (jwsSession != null) {
			UUID uuid = jwsSession.getTunnelUUID();
			if (uuid != null) {
				log.info("JoatseTunnel closed because WS session was closed: {}", uuid);
				cloudTunnelService.removeTunnel(uuid);
			}
			closeSession(wsSession, "WS got closed", null); // This reason will be saved if it was not already closed with
															// a previous reason
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
				
				Collection<TunnelRequestTcpItem> tcpTunnelReqs = new ArrayList<CloudTunnelService.TunnelRequestTcpItem>();
				for (Object o: Optional.ofNullable(js.optJSONArray("tcpTunnels")).orElseGet(()->new JSONArray())) {
					JSONObject jo = (JSONObject) o;
					long targetId = jo.getLong("targetId");
					String targetDescription = jo.optString("targetDescription");
					String targetHostname = jo.optString("targetHostName");
					int targetPort = jo.getInt("targetPort");
					tcpTunnelReqs.add(new TunnelRequestTcpItem(targetId, targetDescription, targetHostname, targetPort));
				}
				
				Collection<TunnelRequestHttpItem> httpTunnelReqs = new ArrayList<CloudTunnelService.TunnelRequestHttpItem>();
				for (Object o: Optional.ofNullable(js.optJSONArray("httpTunnels")).orElseGet(()->new JSONArray())) {
					JSONObject jo = (JSONObject) o;
					long targetId = jo.getLong("targetId");
					String targetDescription = jo.optString("targetDescription");
					URL targetUrl = new URL(jo.optString("targetUrl"));
					httpTunnelReqs.add(new TunnelRequestHttpItem(targetId, targetDescription, targetUrl));
				}

				
				TunnelCreationResponse requestedTunnel = cloudTunnelService.requestTunnel(wsSession.getPrincipal(),
						wsSession.getRemoteAddress(), tcpTunnelReqs, httpTunnelReqs);
				if (!requestedTunnel.result.isDone()) {
					// Not done (not rejected nor error) so we ask the user to use the url to confirm the connection
					wsSessionMap.get(wsSession.getId()).sendMessage((WebSocketMessage<?>) new TextMessage(showUserAcceptanceUriMessage(requestedTunnel)));
				}
				requestedTunnel.result.whenComplete((r,e)->{
					if (e == null && r != null && r.isAccepted()) {
						Accepted acceptedTunnel = (TunnelCreationResult.Accepted)r;
						wsSession.getAttributes().put("uuid", acceptedTunnel.getUuid());
						associateTunnel(wsSession, acceptedTunnel.getTunnel());
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

	private boolean associateTunnel(WebSocketSession wsSession, JoatseTunnel tunnel) {
		JWSSession jWSSession = wsSessionMap.get(wsSession.getId());
		jWSSession.setTunnel(tunnel);
		try {
			/*
			 * This is for http(S) too, for the final link between proxied connection and
			 * websocket to target (and from target to http(s) server)
			 */
			tunnel.setTcpConnectionConsumer(new BiConsumer<Long, AsynchronousSocketChannel>() {
				@Override
				public void accept(Long targetId, AsynchronousSocketChannel t) {
					log.info("Connection arrived from {} for tunnel {}.{} !!", IOTools.runUnchecked(()->t.getRemoteAddress()), tunnel.getUuid(), targetId);
					TcpTunnel tcpItem = tunnel.getTcpItem(targetId);
					HttpTunnel httpTunnel = tunnel.getHttpItem(targetId);
					if (tcpItem == null && httpTunnel == null) {
						log.error("Unknown targetId: " + targetId);
						IOTools.runFailable(()->t.close());
						return;
					}
					TunnelTcpConnection c = new TunnelTcpConnection(jWSSession, t, targetId);
					c.getCloseStatus().thenAccept(remote->{
						// Connection closed ok
						if (remote == null) {
							log.info("TCP tunnel closed");
						} else if (remote) {
							log.info("TCP tunnel closed by target side");
						} else {
							log.info("TCP tunnel closed by this side");
						}
					}).exceptionally(e->{
						log.error("TCP tunnel closed because of error: {}", e, e);
						return null;
					});
				}
			});
		} catch (Exception e2) {
			log.error("Exception processing channel acceptance: {}", e2, e2);
			closeSession(wsSession, "Exception processing channel acceptance", e2);
			return false;
		}
		log.info("JoatseTunnel was succesfully created!!");
		try {
			jWSSession.sendMessage((WebSocketMessage<?>) new TextMessage(runningTunnelMessage(tunnel))).get();
			getStateReference(wsSession).set(State.RUNNING); // Allow to process connections
		} catch (Exception e1) {
			log.error("Exception sending text response message: {}", e1, e1);
			closeSession(wsSession, "Exception sending text response message", e1);
			return false;
		}
		return true;
	}

	private CharSequence runningTunnelMessage(JoatseTunnel tunnel) {
		String cloudPublicHostname = tunnel.getCloudPublicHostname();
		JSONObject res = new JSONObject();
		res.put("request", "CONNECTION");
		res.put("response", "RUNNING");
		Collection<JSONObject> tcpTunnels = new ArrayList<>();
		for (TcpTunnel i: tunnel.getTcpItems()) {
			JSONObject j = new JSONObject();
			j.put("listenHost", cloudPublicHostname);
			j.put("listenPort", i.listenPort);
			j.put("targetHostname", i.targetHostname);
			j.put("targetPort", i.targetPort);
			tcpTunnels.add(j);
		}
		res.put("tcpTunnels", tcpTunnels);
		Collection<JSONObject> httpTunnels = new ArrayList<>();
		for (HttpTunnel i: tunnel.getHttpItems()) {
			JSONObject j = new JSONObject();
			j.put("listenHost", i.getCloudHostname());
			j.put("listenUrl", i.getListenUrl());
			j.put("targetUrl", i.getTargetURL());
			httpTunnels.add(j);
		}
		res.put("httpTunnels", httpTunnels);
		return res.toString();
	}

	private CharSequence showUserAcceptanceUriMessage(TunnelCreationResponse requestedConnection) {
		JSONObject res = new JSONObject();
		res.put("request", "CONNECTION");
		res.put("response", "CONFIRM");
		res.put("confirmationUri", requestedConnection.confirmationUri);
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
				s -> Optional.ofNullable(s.getTunnel()).map(t -> t.getOwner()).map(o -> o.equals(owner)).orElse(false))
				.collect(Collectors.toList());
	}
		
}

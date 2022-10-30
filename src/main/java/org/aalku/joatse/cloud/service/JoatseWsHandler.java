package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.aalku.joatse.cloud.service.CloudTunnelService.ConnectionInstanceProvider;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelCreationResponse;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelCreationResult;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelCreationResult.Accepted;
import org.aalku.joatse.cloud.tools.io.IOTools;
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
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		session.setBinaryMessageSizeLimit(MESSAGE_SIZE_LIMIT);
		HttpHeaders handshakeHeaders = session.getHandshakeHeaders();
		log.info("handshakeHeaders: {} - {}", session.getId(), handshakeHeaders);
		getStateReference(session).set(State.WAITING_COMMAND);
		session.getAttributes().put("jSession", new JoatseSession(session));
	}
	
	private JoatseSession getJSession(WebSocketSession session) {
		return (JoatseSession) session.getAttributes().get("jSession");
	}

	private UUID getUuid(WebSocketSession session) {
		return (UUID) session.getAttributes().get("uuid");
	}
	
	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
		log.info("Ws connection closed: {}", session.getId());
		UUID uuid = getUuid(session);
		if (uuid != null) {
			log.info("Tunnel closed: {}", uuid);
			cloudTunnelService.removeTunnel(uuid);
			closeSession(session, "Ws was already closed", null);
		}
		String closeReason = (String) session.getAttributes().getOrDefault(SESSION_KEY_CLOSE_REASON, "Closed from the client side");
		log.info("Ws session was closed. Reason: {}", closeReason);
	}
	
	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		AtomicReference<State> stateReference = getStateReference(session);
		if (stateReference.get() != State.WAITING_COMMAND) {
			closeSession(session, "Protocol error: Wasn't waiting for text message", null);
			return;
		}
		log.info("handleTextMessage: {} - {}", session.getId(), message.getPayload());
		log.info("session.attributes.before: {} - {}", session.getId(), session.getAttributes());
		try {
			JSONObject js = new JSONObject(message.getPayload());
			String request = js.getString("request");
			if (request.equals("CONNECTION")) {
				String targetHostId = js.optString("targetHostId");
				String targetHostname = js.optString("targetHostName");
				int targetPort = js.getInt("targetPort");
				String targetPortDescription = js.optString("targetPortDescription");
				TunnelCreationResponse requestedTunnel = cloudTunnelService.requestTunnel(session.getPrincipal(), session.getRemoteAddress(), targetHostId, targetHostname, targetPort, targetPortDescription);
				if (!requestedTunnel.result.isDone()) {
					// Not done (not rejected nor error) so we ask the user to use the url to confirm the connection
					getJSession(session).sendMessage((WebSocketMessage<?>) new TextMessage(showUserAcceptanceUriMessage(requestedTunnel)));
				}
				requestedTunnel.result.whenComplete((r,e)->{
					if (e != null) {
						log.error("Rejected connection error: {}", e, e);
					} else if (r.isAccepted()) {
						Accepted acceptedTunnel = (TunnelCreationResult.Accepted)r;
						session.getAttributes().put("uuid", acceptedTunnel.getUuid());
						createTunnel(session, acceptedTunnel);
					} else {
						String rejectionCause = ((TunnelCreationResult.Rejected) r).getRejectionCause();
						log.error("Rejected connection. Cause: {}", rejectionCause);
						try {
							getJSession(session).sendMessage((WebSocketMessage<?>) new TextMessage(rejectionJsonMessage(rejectionCause))).get();
						} catch (Exception e1) {
							log.error("Exception sending text response message: {}", e, e);
							closeSession(session, "Exception sending text response message", e);
						}
					}
				});
				return; // Do not close
			} else {
				throw new IllegalStateException();
			}
		} catch (Exception e) {
			log.error("Exception processing text message: " + e, e);
			closeSession(session, "Exception processing text message", e);
			return;
		} finally {
			log.info("session.attributes.after: {} - {}", session.getId(), session.getAttributes());
		}
	}
	
	@Override
	protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
		JoatseSession jSession = getJSession(session);
		try {
			if (jSession == null) {
				throw new IOException("Unexpected binary message before tunnel creation");
			} else {
				jSession.handleBinaryMessage(message);
			}
		} catch (Exception e) {
			log.error("Exception processing binary message: " + e, e);
			closeSession(session, "Exception processing binary message", e);
		}
	}

	private boolean createTunnel(WebSocketSession session, Accepted acceptedTunnel) {
		JoatseSession manager = getJSession(session);
		try {
			ConnectionInstanceProvider listener = acceptedTunnel.getConnectionInstanceListener();
			listener.setCallback(new Consumer<AsynchronousSocketChannel>() {
				@Override
				public void accept(AsynchronousSocketChannel t) {
					log.info("Connection arrived from {} for tunnel {} !!", IOTools.runUnchecked(()->t.getRemoteAddress()), acceptedTunnel.getUuid());
					TunnelTcpConnection c = new TunnelTcpConnection(manager, t, acceptedTunnel.getTargetPort());
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
			closeSession(session, "Exception processing channel acceptance", e2);
			return false;
		}
		log.info("Tunnel was succesfully created!!");
		try {
			manager.sendMessage((WebSocketMessage<?>) new TextMessage(runningTunnelMessage(acceptedTunnel))).get();
			getStateReference(session).set(State.RUNNING); // Allow to process connections
		} catch (Exception e1) {
			log.error("Exception sending text response message: {}", e1, e1);
			closeSession(session, "Exception sending text response message", e1);
			return false;
		}
		return true;
	}

	private CharSequence runningTunnelMessage(Accepted acceptedTunnel) {
		JSONObject res = new JSONObject();
		res.put("request", "CONNECTION");
		res.put("response", "RUNNING");
		res.put("listenHost", acceptedTunnel.getPublicAddress().getHostString());
		res.put("listenPort", acceptedTunnel.getPublicAddress().getPort());
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
	private void closeSession(WebSocketSession session, String reason, Throwable e) {
		synchronized (session) {
			getStateReference(session).set(State.CLOSED);
			getJSession(session).close(reason, e);
			IOTools.runFailable(()->session.close());
		}
	}
		
}

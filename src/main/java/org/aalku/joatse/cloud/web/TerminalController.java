package org.aalku.joatse.cloud.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.sharing.SharingManager;
import org.aalku.joatse.cloud.service.sharing.command.CommandTunnel;
import org.aalku.joatse.cloud.service.sharing.command.TerminalSessionHandler;
import org.aalku.joatse.cloud.service.sharing.command.TerminalSessionHandler.TerminalUpdateListener;
import org.aalku.joatse.cloud.service.sharing.command.TerminalSessionHandler.TerminalUpdateListener.BytesEvent;
import org.aalku.joatse.cloud.service.sharing.command.TerminalSessionHandler.TerminalUpdateListener.EofEvent;
import org.apache.tomcat.util.buf.HexUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * This class handles the term communication in terms of ws with the display and
 * delegates in TerminalSessionManager the communications to the target and
 * command execution.
 */
@RestController
public class TerminalController extends AbstractWebSocketHandler {

	private static final String WS_SESSION_KEY_JOATSE_TARGET_ID = "JoatseTargetId";
	private static final String WS_SESSION_KEY_JOATSE_SESSION_UUID = "JoatseSessionUUID";
	private static final String WS_SESSION_KEY_JOATSE_SOCKET_ID = "JoatseSocketId";

	@Autowired
	private SharingManager sharingManager;
	
	private static final Logger log = LoggerFactory.getLogger(TerminalController.class);
	
	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		Map<String, String> params = Arrays.asList(session.getUri().getQuery().split("&", -1)).stream()
				.map(e -> e.split("=", 2)).collect(Collectors.toMap(e -> e[0], e -> e[1]));

		UUID uuid = Optional.ofNullable(params.get("uuid")).map(u->UUID.fromString(u)).orElse(null);
		Long targetId = Optional.ofNullable(params.get("targetId")).map(i->Long.parseLong(i)).orElse(null);

		if (uuid == null || targetId == null) {
			log.warn("Disconnecting terminal-ws because incorrectly identified: {}", params);
			session.close(CloseStatus.NOT_ACCEPTABLE);
			return;
		}
		CommandTunnel tunnel = sharingManager.getCommandTunnelById(uuid, targetId);
		if (tunnel == null) {
			log.warn("Disconnecting terminal-ws because unknown tunnel: {}:{}", uuid, targetId);
			session.close(new CloseStatus(4404));
			return;
		}
		log.info("Connected terminal ws: {}", tunnel);
		Map<String, Object> attr = session.getAttributes();
		attr.put(WS_SESSION_KEY_JOATSE_SESSION_UUID, uuid);
		attr.put(WS_SESSION_KEY_JOATSE_TARGET_ID, targetId);
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		try {
			CommandTunnel tunnel = getTunnel(session);
			JSONObject o = new JSONObject(message.getPayload());
			handleJsonMessage(session, tunnel, o);
		} catch (Exception e) {
			log.error(e.toString(), e);
			sendMessage(session, "{ \"error\": \"Internal error\"}");
		}
	}

	private CommandTunnel getTunnel(WebSocketSession session) throws IOException {
		Map<String, Object> attr = session.getAttributes();
		UUID uuid = (UUID) attr.get(WS_SESSION_KEY_JOATSE_SESSION_UUID);
		Long targetId = (Long) attr.get(WS_SESSION_KEY_JOATSE_TARGET_ID);			
		CommandTunnel tunnel = sharingManager.getCommandTunnelById(uuid, targetId);
		if (tunnel == null) {
			log.warn("Disconnecting terminal-ws because unknown tunnel: {}:{}", uuid, targetId);
			session.close(new CloseStatus(4404));
			throw new IOException("Disconnecting terminal-ws because unknown tunnel");
		}
		return tunnel;
	}

	@Override
	public void handleTransportError(WebSocketSession wss, Throwable exception) throws Exception {
		log.warn("Transport error {}: {}", wss, exception);
		wss.close(CloseStatus.PROTOCOL_ERROR);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession wss, CloseStatus closeStatus) throws Exception {
		Map<String, Object> attr = wss.getAttributes();
		Object socketId = attr.get(WS_SESSION_KEY_JOATSE_SOCKET_ID);
		log.info("Terminal closed {}", socketId != null ? socketId : wss);
		if (socketId != null) {
			getTunnel(wss).getSharedResourceLot().getTerminalSessionHandler().handleTerminalClosed((Long)socketId);
		}
	}

	@Override
	public boolean supportsPartialMessages() {
		return false;
	}

	private void handleJsonMessage(WebSocketSession wss, CommandTunnel tunnel, JSONObject data) throws IOException {
		
		TerminalSessionHandler terminalSessionHandler = tunnel.getSharedResourceLot().getTerminalSessionHandler();

		Map<String, Object> attr = wss.getAttributes();
		Long socketId = (Long) attr.get(WS_SESSION_KEY_JOATSE_SOCKET_ID);
		String event = data.getString("event");
		if (event.equals("public-key-request")) {
			tunnel.getSharedResourceLot().getTargetPublicKey().thenAccept(key->{
				try {
					sendMessage(wss, "{ \"cause\": \"public-key-request\", \"publicKey\":\"" + HexUtils.toHexString(key) + "\" }");
				} catch (IOException e) {
					throw new RuntimeException("Error getting public key: " + e, e);
				}
			}).exceptionally(e->{
				log.error("Error getting public key: " + e, e);
				try {
					wss.close(CloseStatus.SERVER_ERROR);
				} catch (IOException e1) {
				}
				return null;
			});
		} else if (event.equals("new-session")) {
			try {
				if (socketId != null) {
					wss.close(CloseStatus.POLICY_VIOLATION);
					return;
				}
				Encoder encoder = Base64.getEncoder();
				TerminalUpdateListener eventHandler = (targetEvent)->{
					JSONObject o = new JSONObject();
					if (targetEvent instanceof BytesEvent) {
						o.put("cause", "update");
						o.put("b64", encoder.encodeToString(((BytesEvent)targetEvent).bytes));
					} else if (targetEvent instanceof EofEvent) {
						o.put("cause", "EOF");
					}
					o.put("stream", targetEvent.getStream());
					try {
						sendMessage(wss, o.toString());
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				};
				String encryptedSessionHex = data.getString("encryptedSessionHex");
				socketId = terminalSessionHandler.newSession(tunnel, eventHandler, encryptedSessionHex);
				attr.put(WS_SESSION_KEY_JOATSE_SOCKET_ID, socketId);
				sendMessage(wss, "{ \"cause\": \"new-session\" }");
			} catch (IOException | RuntimeException e) {
				wss.close(CloseStatus.SERVER_ERROR);
				throw e;
			}
		} else if (event.equals("type")) {
			terminalSessionHandler.type(socketId, ByteBuffer.wrap(Base64.getDecoder().decode(data.getString("textEncodedB64"))));
		} else if (event.equals("resize")) {
			terminalSessionHandler.resize(socketId, data.getInt("rows"), data.getInt("cols"));
		}
	}
		
	public void sendMessage(WebSocketSession wss, CharSequence msg) throws IOException {
		synchronized (wss) {
			wss.sendMessage(new TextMessage(msg));
		}
	}

}

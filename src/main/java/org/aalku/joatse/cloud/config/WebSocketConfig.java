package org.aalku.joatse.cloud.config;

import org.aalku.joatse.cloud.web.TerminalController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.RequestUpgradeStrategy;
import org.springframework.web.socket.server.standard.StandardWebSocketUpgradeStrategy;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

@Configuration
@EnableWebSocket
//@DependsOn("joatseWsHandler")
public class WebSocketConfig implements WebSocketConfigurer {

	public static final String JOATSE_CONNECTION_HTTP_PATH = "/connection";
	public static final String JOATSE_TERMINAL_WS_HTTP_PATH = "/ws-terminal";
	
	@Autowired
	private WebSocketHandler joatseWsHandler;
	
	@Autowired
	private TerminalController terminalController;

	/**
	 * Explicitly use Standard (Jakarta WebSocket) upgrade strategy.
	 * This is necessary because Jetty WebSocket classes are on the classpath (for HTTP proxy client),
	 * which causes Spring to incorrectly auto-detect JettyRequestUpgradeStrategy.
	 */
	@Bean
	public DefaultHandshakeHandler handshakeHandler() {
		RequestUpgradeStrategy upgradeStrategy = new StandardWebSocketUpgradeStrategy();
		return new DefaultHandshakeHandler(upgradeStrategy);
	}

	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(joatseWsHandler, JOATSE_CONNECTION_HTTP_PATH)
				.setHandshakeHandler(handshakeHandler());
		registry.addHandler(terminalController, JOATSE_TERMINAL_WS_HTTP_PATH)
				.setHandshakeHandler(handshakeHandler())
				.withSockJS();
	}
	
}

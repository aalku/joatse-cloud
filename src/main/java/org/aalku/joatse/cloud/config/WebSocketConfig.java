package org.aalku.joatse.cloud.config;

import org.aalku.joatse.cloud.web.TerminalController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

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

	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(joatseWsHandler, JOATSE_CONNECTION_HTTP_PATH);
		registry.addHandler(terminalController, JOATSE_TERMINAL_WS_HTTP_PATH).withSockJS();
	}
	
}

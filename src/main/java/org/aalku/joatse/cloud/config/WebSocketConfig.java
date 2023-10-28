package org.aalku.joatse.cloud.config;

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

	public static final String CONNECTION_HTTP_PATH = "/connection";
	
	@Autowired
	private WebSocketHandler joatseWsHandler;

	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(joatseWsHandler, CONNECTION_HTTP_PATH).withSockJS();
	}

}

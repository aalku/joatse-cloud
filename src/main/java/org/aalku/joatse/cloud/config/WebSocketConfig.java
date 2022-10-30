package org.aalku.joatse.cloud.config;

import org.aalku.joatse.cloud.service.JoatseWsHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	public static final String CONNECTION_HTTP_PATH = "/connection";

	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(connectionHandler(), CONNECTION_HTTP_PATH);
	}

	@Bean
	JoatseWsHandler connectionHandler() {
		return new JoatseWsHandler();
	}

}

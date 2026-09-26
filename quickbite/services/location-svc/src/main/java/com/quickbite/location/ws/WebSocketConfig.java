package com.quickbite.location.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RiderSocketHandler handler;
    public WebSocketConfig(RiderSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/rider").setAllowedOriginPatterns("*"); // native apps send no Origin; tighten for browsers
    }
}
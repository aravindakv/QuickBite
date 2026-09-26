package com.quickbite.realtime.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class UpdatesSocketHandler extends TextWebSocketHandler {
    private final SessionRegistry registry;
    public UpdatesSocketHandler(SessionRegistry registry) { this.registry = registry; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.add(session.getPrincipal().getName(), session);   // principal = JWT "sub"
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getPrincipal() != null) registry.remove(session.getPrincipal().getName(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Clients may send {"type":"PONG"}; nothing else is accepted on this channel.
    }
}
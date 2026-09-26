package com.quickbite.location.ws;

import com.quickbite.location.app.RiderLocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RiderSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RiderSocketHandler.class);
    record Msg(double lat, double lon) {}

    private final RiderLocationService service;
    private final JsonMapper json;
    private final Map<String, Long> lastUpdate = new ConcurrentHashMap<>();

    public RiderSocketHandler(RiderLocationService service, JsonMapper json) { this.service = service; this.json = json; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // The HTTP upgrade request already passed Spring Security (JWT), so the principal is set.
        if (!(session.getPrincipal() instanceof Authentication auth)
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_rider"))) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("riders only"));
            return;
        }
        log.info("rider {} connected", auth.getName());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String riderId = session.getPrincipal().getName();
        long now = System.currentTimeMillis();
        Long prev = lastUpdate.put(riderId, now);
        if (prev != null && now - prev < 1000) return;   // server-side throttle: max 1 update/s per rider
        Msg m = json.readValue(message.getPayload(), Msg.class);
        service.update(riderId, m.lat(), m.lon());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getPrincipal() != null) lastUpdate.remove(session.getPrincipal().getName());
    }
}
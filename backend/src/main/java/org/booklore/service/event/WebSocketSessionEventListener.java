package org.booklore.service.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@Slf4j
public class WebSocketSessionEventListener {

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String principal = event.getUser() != null ? event.getUser().getName() : "(none)";
        log.info("WebSocket STOMP session connected: sessionId={}, principal={}", accessor.getSessionId(), principal);
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        String principal = event.getUser() != null ? event.getUser().getName() : "(none)";
        log.info("WebSocket STOMP session disconnected: sessionId={}, principal={}, status={}", event.getSessionId(), principal, event.getCloseStatus());
    }
}

package com.captionbridge.ingest;

import com.captionbridge.session.SessionService;
import com.captionbridge.session.SessionStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class HostTokenInterceptor implements HandshakeInterceptor {
    static final String SESSION_ID = HostTokenInterceptor.class.getName() + ".sessionId";

    private final SessionService sessions;

    public HostTokenInterceptor(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        UUID sessionId;
        List<String> tokens;
        try {
            var uri = UriComponentsBuilder.fromUri(request.getURI()).build(true);
            String id = uri.getPathSegments().getLast();
            sessionId = UUID.fromString(id);
            if (!sessionId.toString().equalsIgnoreCase(id)) {
                response.setStatusCode(HttpStatus.BAD_REQUEST);
                return false;
            }
            tokens = uri.getQueryParams().get("token");
        } catch (IllegalArgumentException | java.util.NoSuchElementException e) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        if (tokens != null && tokens.size() != 1) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        String token = tokens == null ? null : tokens.getFirst();
        try {
            if (sessions.authorizeHost(sessionId, token).status() != SessionStatus.LIVE) {
                response.setStatusCode(HttpStatus.CONFLICT);
                return false;
            }
        } catch (ResponseStatusException e) {
            response.setStatusCode(e.getStatusCode());
            return false;
        }
        attributes.put(SESSION_ID, sessionId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception failure) {
    }
}

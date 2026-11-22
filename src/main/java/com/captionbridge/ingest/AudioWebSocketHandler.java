package com.captionbridge.ingest;

import com.captionbridge.caption.CaptionService;
import com.captionbridge.session.SessionService;
import com.captionbridge.session.SessionStatus;
import com.captionbridge.stt.SpeechToTextProvider;
import com.captionbridge.stt.SttListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AudioWebSocketHandler extends BinaryWebSocketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AudioWebSocketHandler.class);
    private static final int MAX_PCM_BYTES = 32_000;
    private static final String STREAM = AudioWebSocketHandler.class.getName() + ".stream";
    private static final CloseStatus INVALID_PCM = CloseStatus.BAD_DATA.withReason("Expected PCM16 audio");

    private final SpeechToTextProvider provider;
    private final CaptionService captions;
    private final SessionService sessions;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final Map<UUID, WebSocketSession> activeHosts = new ConcurrentHashMap<>();

    public AudioWebSocketHandler(SpeechToTextProvider provider, CaptionService captions, SessionService sessions,
                                 ApplicationEventPublisher events, ObjectMapper json) {
        this.provider = provider;
        this.captions = captions;
        this.sessions = sessions;
        this.events = events;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws IOException {
        UUID sessionId = sessionId(socket);
        try (var ignored = MDC.putCloseable("sessionId", sessionId.toString())) {
            if (activeHosts.putIfAbsent(sessionId, socket) != null) {
                sendErrorAndClose(socket, "A host microphone is already connected", CloseStatus.POLICY_VIOLATION);
                return;
            }
            SpeechToTextProvider.Stream stream;
            try {
                if (sessions.get(sessionId).status() != SessionStatus.LIVE) {
                    activeHosts.remove(sessionId, socket);
                    socket.close(CloseStatus.NORMAL.withReason("Event ended"));
                    return;
                }
                socket.setBinaryMessageSizeLimit(MAX_PCM_BYTES);
                stream = provider.open(listener(sessionId));
            } catch (ResponseStatusException e) {
                activeHosts.remove(sessionId, socket);
                socket.close(CloseStatus.NORMAL.withReason("Event ended"));
                return;
            } catch (IOException | RuntimeException e) {
                activeHosts.remove(sessionId, socket);
                LOG.warn("Could not open speech recognition", e);
                sendErrorAndClose(socket, "Speech recognition is unavailable", CloseStatus.SERVER_ERROR);
                return;
            }

            socket.getAttributes().put(STREAM, stream);
            if (!socket.isOpen()) {
                release(socket);
                return;
            }
            try {
                sendStatus(socket, "ready", null);
            } catch (IOException | RuntimeException e) {
                release(socket);
                throw e;
            }
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) throws IOException {
        var stream = (SpeechToTextProvider.Stream) socket.getAttributes().get(STREAM);
        if (stream == null) {
            return;
        }

        int length = message.getPayloadLength();
        if (length > MAX_PCM_BYTES) {
            sendErrorAndClose(socket, "Audio frame is too large", CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        if (length == 0 || length % Short.BYTES != 0) {
            sendErrorAndClose(socket, "Audio frame must contain complete 16-bit samples", INVALID_PCM);
            return;
        }
        byte[] pcm = new byte[length];
        message.getPayload().asReadOnlyBuffer().get(pcm);
        try (var ignored = MDC.putCloseable("sessionId", sessionId(socket).toString())) {
            try {
                stream.acceptPcm(pcm);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not process microphone audio", e);
                sendErrorAndClose(socket, "Speech recognition failed", CloseStatus.SERVER_ERROR);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable failure) throws IOException {
        try (var ignored = MDC.putCloseable("sessionId", sessionId(socket).toString())) {
            LOG.debug("Host WebSocket transport error ({})", failure.getClass().getSimpleName());
        }
        try {
            if (socket.isOpen()) {
                socket.close(CloseStatus.SERVER_ERROR);
            }
        } finally {
            release(socket);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        release(socket);
    }

    @TransactionalEventListener
    public void sessionEnded(SessionService.SessionEnded ended) {
        closeHost(ended.sessionId());
    }

    @TransactionalEventListener
    public void sessionDeleted(SessionService.SessionDeleted deleted) {
        closeHost(deleted.sessionId());
    }

    private void closeHost(UUID sessionId) {
        WebSocketSession socket = activeHosts.get(sessionId);
        if (socket != null && socket.isOpen()) {
            try (var ignored = MDC.putCloseable("sessionId", sessionId.toString())) {
                try {
                    socket.close(CloseStatus.NORMAL.withReason("Event ended"));
                } catch (IOException e) {
                    LOG.debug("Could not close host WebSocket", e);
                }
            }
        }
    }

    private SttListener listener(UUID sessionId) {
        return new SttListener() {
            @Override
            public void onPartial(String text) {
                publishCaption(() -> events.publishEvent(captions.partial(sessionId, text)));
            }

            @Override
            public void onFinal(String text) {
                // appendFinal returns after the caption's transaction commits.
                publishCaption(() -> events.publishEvent(captions.appendFinal(sessionId, text)));
            }

            private void publishCaption(Runnable publish) {
                try (var ignored = MDC.putCloseable("sessionId", sessionId.toString())) {
                    try {
                        publish.run();
                    } catch (ResponseStatusException e) {
                        LOG.debug("Ignoring recognition result for inactive session ({})", e.getStatusCode());
                    }
                }
            }
        };
    }

    private void release(WebSocketSession socket) {
        var stream = (SpeechToTextProvider.Stream) socket.getAttributes().remove(STREAM);
        if (stream == null) {
            return;
        }

        UUID sessionId = sessionId(socket);
        try (var ignored = MDC.putCloseable("sessionId", sessionId.toString())) {
            stream.close();
        } catch (IOException | RuntimeException e) {
            try (var ignored = MDC.putCloseable("sessionId", sessionId.toString())) {
                LOG.warn("Could not finish speech recognition", e);
            }
        } finally {
            activeHosts.remove(sessionId, socket);
        }
    }

    private void sendErrorAndClose(WebSocketSession socket, String message, CloseStatus status) throws IOException {
        try {
            sendStatus(socket, "error", message);
        } finally {
            try {
                socket.close(status);
            } finally {
                release(socket);
            }
        }
    }

    private void sendStatus(WebSocketSession socket, String status, String message) throws IOException {
        socket.sendMessage(new TextMessage(json.writeValueAsString(new HostStatus("status", status, message))));
    }

    private static UUID sessionId(WebSocketSession socket) {
        return (UUID) socket.getAttributes().get(HostTokenInterceptor.SESSION_ID);
    }

    private record HostStatus(String type, String status, String message) {
    }
}

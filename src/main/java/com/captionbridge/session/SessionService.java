package com.captionbridge.session;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class SessionService {
    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_LENGTH = 43;

    private final SessionRepository sessions;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public SessionService(SessionRepository sessions, ApplicationEventPublisher events) {
        this.sessions = sessions;
        this.events = events;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreatedSession create(String title) {
        if (title == null || title.isBlank() || title.strip().length() > Session.MAX_TITLE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title must contain 1 to 120 characters");
        }

        sessions.lockCreationGuard();
        if (sessions.existsByStatus(SessionStatus.LIVE)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A session is already live");
        }

        byte[] tokenBytes = new byte[TOKEN_BYTES];
        random.nextBytes(tokenBytes);
        String hostToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Session session = new Session(UUID.randomUUID(), title.strip(),
                HexFormat.of().formatHex(tokenHash(hostToken)), Instant.now().truncatedTo(ChronoUnit.MICROS));
        sessions.save(session);
        return new CreatedSession(session.id(), hostToken);
    }

    @Transactional(readOnly = true)
    public SessionInfo get(UUID sessionId) {
        return requireSession(sessionId).info();
    }

    @Transactional(readOnly = true)
    public SessionInfo authorizeHost(UUID sessionId, String hostToken) {
        return requireHost(requireSession(sessionId), hostToken).info();
    }

    @Transactional
    public SessionInfo end(UUID sessionId, String hostToken) {
        Session session = requireHost(lockSession(sessionId), hostToken);
        if (session.isLive()) {
            session.end(Instant.now().truncatedTo(ChronoUnit.MICROS));
            events.publishEvent(new SessionEnded(sessionId));
        }
        return session.info();
    }

    @Transactional
    public void delete(UUID sessionId, String hostToken) {
        sessions.delete(requireHost(lockSession(sessionId), hostToken));
        events.publishEvent(new SessionDeleted(sessionId));
    }

    @Transactional(readOnly = true)
    public long nextCaptionSeq(UUID sessionId) {
        return requireLive(requireSession(sessionId)).nextCaptionSeq();
    }

    /** Reserves a sequence inside the same transaction that will insert its caption. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long reserveCaptionSeq(UUID sessionId) {
        return requireLive(lockSession(sessionId)).reserveCaptionSeq();
    }

    private Session requireSession(UUID sessionId) {
        return sessions.findById(sessionId).orElseThrow(SessionService::sessionNotFound);
    }

    private Session lockSession(UUID sessionId) {
        return sessions.findByIdForUpdate(sessionId).orElseThrow(SessionService::sessionNotFound);
    }

    private static ResponseStatusException sessionNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
    }

    private static Session requireLive(Session session) {
        if (!session.isLive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session has ended");
        }
        return session;
    }

    private static Session requireHost(Session session, String hostToken) {
        if (hostToken == null || hostToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Host token required");
        }
        if (hostToken.length() != TOKEN_LENGTH ||
                !MessageDigest.isEqual(HexFormat.of().parseHex(session.hostTokenHash()), tokenHash(hostToken))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid host token");
        }
        return session;
    }

    private static byte[] tokenHash(String hostToken) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(hostToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record SessionEnded(UUID sessionId) {
    }

    public record SessionDeleted(UUID sessionId) {
    }

    public record CreatedSession(UUID sessionId, String hostToken) {
        @Override
        public String toString() {
            return "CreatedSession[sessionId=" + sessionId + "]";
        }
    }

    public record SessionInfo(UUID sessionId, String title, SessionStatus status, Instant createdAt, Instant endedAt) {
    }
}

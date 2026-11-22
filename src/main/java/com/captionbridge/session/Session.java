package com.captionbridge.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class Session {
    static final int MAX_TITLE_LENGTH = 120;

    @Id
    private UUID id;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionStatus status;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    private String hostTokenHash;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant endedAt;

    @Column(nullable = false)
    private long lastCaptionSeq;

    protected Session() {
    }

    Session(UUID id, String title, String hostTokenHash, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.hostTokenHash = hostTokenHash;
        this.createdAt = createdAt;
        this.status = SessionStatus.LIVE;
    }

    UUID id() {
        return id;
    }

    String hostTokenHash() {
        return hostTokenHash;
    }

    boolean isLive() {
        return status == SessionStatus.LIVE;
    }

    SessionService.SessionInfo info() {
        return new SessionService.SessionInfo(id, title, status, createdAt, endedAt);
    }

    void end(Instant now) {
        if (isLive()) {
            status = SessionStatus.ENDED;
            endedAt = now;
        }
    }

    long nextCaptionSeq() {
        return Math.incrementExact(lastCaptionSeq);
    }

    long reserveCaptionSeq() {
        lastCaptionSeq = nextCaptionSeq();
        return lastCaptionSeq;
    }
}

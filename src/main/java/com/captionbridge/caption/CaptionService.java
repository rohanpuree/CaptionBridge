package com.captionbridge.caption;

import com.captionbridge.session.SessionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CaptionService {
    private final CaptionRepository captions;
    private final SessionService sessions;

    public CaptionService(CaptionRepository captions, SessionService sessions) {
        this.captions = captions;
        this.sessions = sessions;
    }

    @Transactional
    public CaptionLine appendFinal(UUID sessionId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Final caption text must not be blank");
        }
        long seq = sessions.reserveCaptionSeq(sessionId);
        return captions.save(new CaptionLine(sessionId, seq, text, Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    @Transactional(readOnly = true)
    public PartialCaption partial(UUID sessionId, String text) {
        Objects.requireNonNull(text, "Partial caption text is required");
        return new PartialCaption(sessionId, sessions.nextCaptionSeq(sessionId), text);
    }

    @Transactional(readOnly = true)
    public List<CaptionLine> readAfter(UUID sessionId, long after, int limit) {
        if (after < 0 || limit < 1) {
            throw new IllegalArgumentException("Cursor must be nonnegative and limit must be positive");
        }
        return captions.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, after, PageRequest.of(0, limit));
    }
}

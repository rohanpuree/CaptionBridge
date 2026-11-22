package com.captionbridge.delivery;

import com.captionbridge.caption.CaptionLine;
import com.captionbridge.caption.CaptionService;
import com.captionbridge.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions/{id}")
public class AttendeeStreamController {
    private final SessionService sessions;
    private final CaptionService captions;
    private final SseBroadcaster broadcaster;

    public AttendeeStreamController(SessionService sessions, CaptionService captions, SseBroadcaster broadcaster) {
        this.sessions = sessions;
        this.captions = captions;
        this.broadcaster = broadcaster;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @PathVariable UUID id,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(name = "after", required = false) String after,
            HttpServletRequest request) {
        long cursor = parseCursor(lastEventId == null || lastEventId.isBlank() ? after : lastEventId);
        sessions.get(id);
        SseEmitter emitter = new SseEmitter(0L);
        request.setAttribute(SseStartFilter.ON_READY, (Runnable) () -> broadcaster.subscribe(id, cursor, emitter));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    @GetMapping("/captions")
    public List<CaptionLine> captions(@PathVariable UUID id,
                                      @RequestParam(defaultValue = "0") String after,
                                      @RequestParam(defaultValue = "100") int limit) {
        long cursor = parseCursor(after);
        if (limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be between 1 and 500");
        }
        sessions.get(id);
        return captions.readAfter(id, cursor, limit);
    }

    private static long parseCursor(String value) {
        if (value == null) {
            return 0;
        }
        try {
            long cursor = Long.parseLong(value);
            if (cursor >= 0) {
                return cursor;
            }
        } catch (NumberFormatException ignored) {
            // Return the same response for all invalid cursors.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Caption cursor must be a nonnegative integer");
    }
}

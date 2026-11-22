package com.captionbridge.session;

import com.captionbridge.config.JoinUrl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    public static final String HOST_TOKEN_HEADER = "X-Host-Token";

    private final SessionService sessions;
    private final SessionCreationLimiter limiter;
    private final JoinUrl joinUrl;

    public SessionController(SessionService sessions, SessionCreationLimiter limiter, JoinUrl joinUrl) {
        this.sessions = sessions;
        this.limiter = limiter;
        this.joinUrl = joinUrl;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Created> create(@Valid @RequestBody CreateSession body, HttpServletRequest request) {
        if (!limiter.allow(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Integer.toString(SessionCreationLimiter.RETRY_AFTER_SECONDS))
                    .build();
        }
        var created = sessions.create(body.title());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new Created(created.sessionId(), joinUrl.forSession(request, created.sessionId()),
                        created.hostToken()));
    }

    @GetMapping("/{id}")
    public SessionService.SessionInfo get(@PathVariable UUID id) {
        return sessions.get(id);
    }

    @PostMapping("/{id}/end")
    public SessionService.SessionInfo end(@PathVariable UUID id,
                                          @RequestHeader(name = HOST_TOKEN_HEADER, required = false) String token) {
        return sessions.end(id, token);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                      @RequestHeader(name = HOST_TOKEN_HEADER, required = false) String token) {
        sessions.delete(id, token);
        return ResponseEntity.noContent().build();
    }

    public record CreateSession(@NotBlank @Size(max = Session.MAX_TITLE_LENGTH) String title) {
    }

    public record Created(UUID sessionId, String joinUrl, String hostToken) {
        @Override
        public String toString() {
            return "Created[sessionId=" + sessionId + ", joinUrl=" + joinUrl + "]";
        }
    }
}

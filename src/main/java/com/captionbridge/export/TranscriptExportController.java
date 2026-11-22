package com.captionbridge.export;

import com.captionbridge.caption.CaptionLine;
import com.captionbridge.caption.CaptionRepository;
import com.captionbridge.session.SessionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.captionbridge.session.SessionController.HOST_TOKEN_HEADER;

@RestController
public class TranscriptExportController {
    private static final PageRequest BATCH = PageRequest.of(0, 500);
    private static final MediaType UTF_8_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final MediaType UTF_8_VTT = new MediaType("text", "vtt", StandardCharsets.UTF_8);
    private final SessionService sessions;
    private final CaptionRepository captions;

    public TranscriptExportController(SessionService sessions, CaptionRepository captions) {
        this.sessions = sessions;
        this.captions = captions;
    }

    @GetMapping("/api/sessions/{id}/export")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<String> export(@PathVariable UUID id,
                                         @RequestHeader(name = HOST_TOKEN_HEADER, required = false) String token,
                                         @RequestParam String format) {
        var session = sessions.authorizeHost(id, token);
        MediaType contentType = switch (format) {
            case "txt", "srt" -> UTF_8_TEXT;
            case "vtt" -> UTF_8_VTT;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Format must be txt, srt or vtt");
        };
        var lines = transcript(id);
        String output = switch (format) {
            case "srt" -> new SrtFormatter().format(session.createdAt(), lines);
            case "vtt" -> new VttFormatter().format(session.createdAt(), lines);
            default -> new TxtFormatter().format(lines);
        };
        var filename = ContentDisposition.attachment().filename("captionbridge-" + id + "." + format).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, filename.toString())
                .body(output);
    }

    private List<CaptionLine> transcript(UUID id) {
        var lines = new ArrayList<CaptionLine>();
        long after = 0;
        List<CaptionLine> batch;
        do {
            batch = captions.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(id, after, BATCH);
            lines.addAll(batch);
            if (!batch.isEmpty()) {
                after = batch.getLast().getSeq();
            }
        } while (batch.size() == BATCH.getPageSize());
        return lines;
    }
}

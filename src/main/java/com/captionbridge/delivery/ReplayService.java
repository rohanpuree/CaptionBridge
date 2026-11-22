package com.captionbridge.delivery;

import com.captionbridge.caption.CaptionBuffer;
import com.captionbridge.caption.CaptionLine;
import com.captionbridge.caption.CaptionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ReplayService {
    private final CaptionBuffer buffer;
    private final CaptionService captions;

    public ReplayService(CaptionBuffer buffer, CaptionService captions) {
        this.buffer = buffer;
        this.captions = captions;
    }

    /** Returns a contiguous batch and falls back to storage when the cache has a gap. */
    public List<CaptionLine> readAfter(UUID sessionId, long after, int limit) {
        var recent = buffer.readAfter(sessionId, after, limit);
        return recent.isEmpty() ? captions.readAfter(sessionId, after, limit) : recent;
    }
}

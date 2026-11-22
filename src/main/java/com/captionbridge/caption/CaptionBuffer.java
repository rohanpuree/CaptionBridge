package com.captionbridge.caption;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/** Keeps a bounded set of recently published finals for replay. */
@Component
public class CaptionBuffer {
    private static final int SESSION_LIMIT = 16;
    private static final int CAPTIONS_PER_SESSION = 256;

    private final LinkedHashMap<UUID, NavigableMap<Long, CaptionLine>> recent =
            new LinkedHashMap<>(SESSION_LIMIT, 0.75f, true);

    public synchronized void add(CaptionLine caption) {
        var lines = recent.computeIfAbsent(caption.getSessionId(), id -> new TreeMap<>());
        lines.put(caption.getSeq(), caption);
        if (lines.size() > CAPTIONS_PER_SESSION) {
            lines.pollFirstEntry();
        }
        if (recent.size() > SESSION_LIMIT) {
            recent.pollFirstEntry();
        }
    }

    public synchronized List<CaptionLine> readAfter(UUID sessionId, long after, int limit) {
        var lines = recent.get(sessionId);
        if (lines == null) {
            return List.of();
        }

        var batch = new ArrayList<CaptionLine>();
        long cursor = after;
        for (var entry : lines.tailMap(after, false).entrySet()) {
            if (entry.getKey() - 1 != cursor || batch.size() == limit) {
                break;
            }
            batch.add(entry.getValue());
            cursor = entry.getKey();
        }
        return batch;
    }

    public synchronized void remove(UUID sessionId) {
        recent.remove(sessionId);
    }
}

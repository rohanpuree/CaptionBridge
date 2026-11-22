package com.captionbridge.export;

import com.captionbridge.caption.CaptionLine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

final class TimedTranscript {
    private static final long MAX_CUE_MILLIS = 3_000;

    private TimedTranscript() {
    }

    static void appendTo(StringBuilder output, Instant sessionStart, List<CaptionLine> lines, boolean webVtt) {
        char separator = webVtt ? '.' : ',';
        long start = lines.isEmpty() ? 0 : offset(sessionStart, lines.getFirst());
        for (int index = 0; index < lines.size(); index++) {
            long next = index + 1 < lines.size()
                    ? Math.max(start + 1, offset(sessionStart, lines.get(index + 1)))
                    : start + MAX_CUE_MILLIS;
            long end = Math.min(next, start + MAX_CUE_MILLIS);
            String text = lines.get(index).getText().replaceAll("\\R+", " ").strip();
            if (webVtt) {
                text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            }
            output.append(index + 1).append('\n')
                    .append(timestamp(start, separator)).append(" --> ").append(timestamp(end, separator)).append('\n')
                    .append(text).append("\n\n");
            start = next;
        }
    }

    private static long offset(Instant sessionStart, CaptionLine line) {
        return Math.max(0, Duration.between(sessionStart, line.getCreatedAt()).toMillis());
    }

    private static String timestamp(long millis, char separator) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d",
                millis / 3_600_000, millis / 60_000 % 60, millis / 1_000 % 60, separator, millis % 1_000);
    }
}

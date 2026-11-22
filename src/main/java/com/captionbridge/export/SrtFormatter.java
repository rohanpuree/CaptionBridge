package com.captionbridge.export;

import com.captionbridge.caption.CaptionLine;

import java.time.Instant;
import java.util.List;

public final class SrtFormatter {
    public String format(Instant sessionStart, List<CaptionLine> lines) {
        var output = new StringBuilder();
        TimedTranscript.appendTo(output, sessionStart, lines, false);
        return output.toString();
    }
}

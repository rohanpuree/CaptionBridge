package com.captionbridge.export;

import com.captionbridge.caption.CaptionLine;

import java.time.Instant;
import java.util.List;

public final class VttFormatter {
    public String format(Instant sessionStart, List<CaptionLine> lines) {
        var output = new StringBuilder("WEBVTT\n\n");
        TimedTranscript.appendTo(output, sessionStart, lines, true);
        return output.toString();
    }
}

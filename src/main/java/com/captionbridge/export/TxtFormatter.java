package com.captionbridge.export;

import com.captionbridge.caption.CaptionLine;

import java.util.List;

public final class TxtFormatter {
    public String format(List<CaptionLine> lines) {
        var transcript = new StringBuilder();
        for (CaptionLine line : lines) {
            transcript.append(line.getText()).append('\n');
        }
        return transcript.toString();
    }
}

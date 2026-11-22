package com.captionbridge.export;

import com.captionbridge.caption.CaptionLine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranscriptFormatterTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void txtPreservesTextAndUnicode() {
        var lines = List.of(caption(200, "Résumé"), caption(500, "Second line"));
        assertThat(new TxtFormatter().format(lines)).isEqualTo("Résumé\nSecond line\n");
        assertThat(new TxtFormatter().format(List.of())).isEmpty();
    }

    @Test
    void srtUsesCommaTimestampsAndPreventsOverlappingCues() {
        var lines = List.of(caption(-200, "First\nline"), caption(0, "Second"));
        assertThat(new SrtFormatter().format(START, lines)).isEqualTo("""
                1
                00:00:00,000 --> 00:00:00,001
                First line

                2
                00:00:00,001 --> 00:00:03,001
                Second

                """);
        assertThat(new SrtFormatter().format(START, List.of())).isEmpty();
    }

    @Test
    void vttUsesPeriodTimestampsAndEscapesCueMarkup() {
        var lines = List.of(caption(250, "<host> & attendees"), caption(4_000, "Next"));
        assertThat(new VttFormatter().format(START, lines)).isEqualTo("""
                WEBVTT

                1
                00:00:00.250 --> 00:00:03.250
                &lt;host&gt; &amp; attendees

                2
                00:00:04.000 --> 00:00:07.000
                Next

                """);
        assertThat(new VttFormatter().format(START, List.of())).isEqualTo("WEBVTT\n\n");
    }

    private static CaptionLine caption(long offset, String text) {
        var line = mock(CaptionLine.class);
        when(line.getText()).thenReturn(text);
        when(line.getCreatedAt()).thenReturn(START.plusMillis(offset));
        return line;
    }
}

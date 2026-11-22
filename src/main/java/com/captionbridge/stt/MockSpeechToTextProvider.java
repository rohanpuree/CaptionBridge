package com.captionbridge.stt;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MockSpeechToTextProvider implements SpeechToTextProvider, AutoCloseable {
    private static final List<String> SCRIPT = List.of(
            "Welcome to CaptionBridge.",
            "These captions are generated in demo mode.",
            "Open the attendee page on another device.",
            "Disconnect and reconnect to see the saved captions catch up.");

    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("mock-stt").factory());

    @Override
    public Stream open(SttListener listener) {
        return new MockStream(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void close() {
        timer.shutdown();
    }

    private final class MockStream implements Stream {
        private final SttListener listener;
        private final ScheduledFuture<?> task;
        private int lineIndex;
        private int wordIndex;
        private boolean closed;

        private MockStream(SttListener listener) {
            this.listener = listener;
            task = timer.scheduleWithFixedDelay(this::emitNext, 550, 550, TimeUnit.MILLISECONDS);
        }

        @Override
        public synchronized void acceptPcm(byte[] pcm) {
            if (closed) {
                throw new IllegalStateException("Mock stream is closed");
            }
            if (pcm.length % Short.BYTES != 0) {
                throw new IllegalArgumentException("PCM frame must contain complete 16-bit samples");
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            task.cancel(false);
        }

        private synchronized void emitNext() {
            if (closed) {
                return;
            }
            String line = SCRIPT.get(lineIndex);
            wordIndex = line.indexOf(' ', wordIndex + 1);
            if (wordIndex >= 0) {
                listener.onPartial(line.substring(0, wordIndex));
            } else {
                lineIndex = (lineIndex + 1) % SCRIPT.size();
                wordIndex = 0;
                listener.onFinal(line);
            }
        }
    }
}

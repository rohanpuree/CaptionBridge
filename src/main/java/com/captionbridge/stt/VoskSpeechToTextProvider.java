package com.captionbridge.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class VoskSpeechToTextProvider implements SpeechToTextProvider, AutoCloseable {
    private final Path modelPath;
    private final ObjectMapper json;
    private Model model;
    private boolean closed;

    public VoskSpeechToTextProvider(Path modelPath, ObjectMapper json) {
        this.modelPath = modelPath.toAbsolutePath().normalize();
        this.json = json;
    }

    @Override
    public synchronized Stream open(SttListener listener) throws IOException {
        Objects.requireNonNull(listener, "listener");
        if (closed) {
            throw new IllegalStateException("Vosk provider is closed");
        }
        if (model == null && !Files.isDirectory(modelPath)) {
            throw new IOException("Vosk model directory was not found at " + modelPath
                    + ". Set CAPTIONBRIDGE_STT_VOSK_MODEL_PATH or use the demo profile.");
        }
        try {
            if (model == null) {
                model = new Model(modelPath.toString());
            }
            return new VoskStream(new Recognizer(model, SAMPLE_RATE_HZ), listener);
        } catch (IOException e) {
            throw new IOException("Could not initialize Vosk recognition with model at " + modelPath, e);
        } catch (LinkageError e) {
            throw new IOException("Could not load the native Vosk library", e);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (model != null) {
            model.close();
            model = null;
        }
    }

    private final class VoskStream implements Stream {
        private final Recognizer recognizer;
        private final SttListener listener;
        private String lastPartial = "";
        private boolean closed;

        private VoskStream(Recognizer recognizer, SttListener listener) {
            this.recognizer = recognizer;
            this.listener = listener;
        }

        @Override
        public synchronized void acceptPcm(byte[] pcm) throws IOException {
            if (closed) {
                throw new IllegalStateException("Vosk stream is closed");
            }
            if (pcm.length % Short.BYTES != 0) {
                throw new IllegalArgumentException("PCM frame must contain complete 16-bit samples");
            }
            if (pcm.length == 0) {
                return;
            }
            if (recognizer.acceptWaveForm(pcm, pcm.length)) {
                publishFinal(recognizer.getResult());
            } else {
                publishPartial(decode(recognizer.getPartialResult(), "partial"));
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try (recognizer) {
                publishFinal(recognizer.getFinalResult());
            }
        }

        private void publishPartial(String text) {
            if (!text.equals(lastPartial)) {
                lastPartial = text;
                listener.onPartial(text);
            }
        }

        private void publishFinal(String result) throws IOException {
            String text = decode(result, "text");
            if (text.isBlank()) {
                publishPartial("");
            } else {
                lastPartial = "";
                listener.onFinal(text);
            }
        }

        private String decode(String result, String field) throws IOException {
            return json.readTree(result).path(field).asText("").strip();
        }
    }
}

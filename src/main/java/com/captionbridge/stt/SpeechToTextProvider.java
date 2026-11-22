package com.captionbridge.stt;

import java.io.IOException;

/** Creates an independent recognizer for each host audio stream. */
public interface SpeechToTextProvider {
    int SAMPLE_RATE_HZ = 16_000;

    /**
     * Starts recognition. The listener can receive callbacks on a provider thread.
     * Providers serialize callbacks within each stream.
     */
    Stream open(SttListener listener) throws IOException;

    /** Owns the recognition state of one audio stream. */
    interface Stream extends AutoCloseable {
        /**
         * Accepts signed 16-bit little-endian mono PCM at 16 kHz.
         * The caller retains ownership of the buffer.
         */
        void acceptPcm(byte[] pcm) throws IOException;

        /**
         * Flushes any available final result and releases resources. Closing twice is safe.
         * No further callbacks occur once this method returns.
         */
        @Override
        void close() throws IOException;
    }
}

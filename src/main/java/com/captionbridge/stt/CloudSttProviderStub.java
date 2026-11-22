package com.captionbridge.stt;

import java.io.IOException;

/**
 * Placeholder for Google Cloud Speech-to-Text streaming. A future implementation
 * would open an authenticated bidirectional recognition stream per host, send
 * LINEAR16 audio at 16 kHz, and map interim and final responses to the listener.
 * This placeholder starts no network connections and requires no credentials.
 */
public final class CloudSttProviderStub implements SpeechToTextProvider {
    @Override
    public Stream open(SttListener listener) throws IOException {
        throw new IOException("Google Cloud Speech-to-Text streaming is not implemented. Select vosk or mock.");
    }
}

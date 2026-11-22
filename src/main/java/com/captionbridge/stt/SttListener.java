package com.captionbridge.stt;

/** Receives provisional hypotheses and finalized recognition results in recognition order. */
public interface SttListener {
    /** Replaces the current provisional text. An empty string clears it. */
    void onPartial(String text);

    /** Completes the current line with nonblank text. */
    void onFinal(String text);
}

package com.captionbridge.caption;

import java.util.UUID;

public record PartialCaption(UUID sessionId, long seq, String text) {
}

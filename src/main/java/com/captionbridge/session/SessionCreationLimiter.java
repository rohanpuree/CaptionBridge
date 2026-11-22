package com.captionbridge.session;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
class SessionCreationLimiter {
    static final int RETRY_AFTER_SECONDS = 60;
    private static final long WINDOW_NANOS = Duration.ofSeconds(RETRY_AFTER_SECONDS).toNanos();
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_CLIENTS = 4096;

    private final Map<String, Window> clients = new HashMap<>();

    synchronized boolean allow(String address) {
        long now = System.nanoTime();
        var window = clients.get(address);
        if (window == null && clients.size() >= MAX_CLIENTS) {
            clients.values().removeIf(entry -> now - entry.startedAt() >= WINDOW_NANOS);
            if (clients.size() >= MAX_CLIENTS) {
                return false;
            }
        }
        if (window == null || now - window.startedAt() >= WINDOW_NANOS) {
            clients.put(address, new Window(now, 1));
            return true;
        }
        if (window.attempts() >= MAX_ATTEMPTS) {
            return false;
        }
        clients.put(address, new Window(window.startedAt(), window.attempts() + 1));
        return true;
    }

    private record Window(long startedAt, int attempts) {
    }
}

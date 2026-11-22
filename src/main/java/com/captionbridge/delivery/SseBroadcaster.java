package com.captionbridge.delivery;

import com.captionbridge.caption.CaptionBuffer;
import com.captionbridge.caption.CaptionLine;
import com.captionbridge.caption.PartialCaption;
import com.captionbridge.session.SessionService;
import com.captionbridge.session.SessionStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SseBroadcaster {
    private static final Logger LOG = LoggerFactory.getLogger(SseBroadcaster.class);
    private static final int REPLAY_BATCH = 100;
    private static final int QUEUE_CAPACITY = 128;
    private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(15);

    private final SessionService sessions;
    private final ReplayService replay;
    private final CaptionBuffer buffer;
    private final ConcurrentHashMap<UUID, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();

    public SseBroadcaster(SessionService sessions, ReplayService replay, CaptionBuffer buffer) {
        this.sessions = sessions;
        this.replay = replay;
        this.buffer = buffer;
    }

    void subscribe(UUID sessionId, long after, SseEmitter emitter) {
        var subscriber = new Subscriber(sessionId, after, emitter);
        subscribers.compute(sessionId, (id, current) -> {
            var group = current == null ? ConcurrentHashMap.<Subscriber>newKeySet() : current;
            group.add(subscriber);
            return group;
        });
        emitter.onCompletion(subscriber::stop);
        emitter.onError(error -> subscriber.stop());
        emitter.onTimeout(subscriber::stop);
        subscriber.worker.start();
    }

    @EventListener
    public void finalCaption(CaptionLine caption) {
        buffer.add(caption);
        publish(caption.getSessionId(), Signal.CATCH_UP);
    }

    @EventListener
    public void partialCaption(PartialCaption partial) {
        publish(partial.sessionId(), new Partial(partial));
    }

    @TransactionalEventListener
    public void sessionEnded(SessionService.SessionEnded ended) {
        publish(ended.sessionId(), Signal.ENDED);
    }

    @TransactionalEventListener
    public void sessionDeleted(SessionService.SessionDeleted deleted) {
        buffer.remove(deleted.sessionId());
        publish(deleted.sessionId(), Signal.DELETED);
    }

    @PreDestroy
    void shutdown() {
        subscribers.values().forEach(group -> group.forEach(Subscriber::stop));
    }

    private void publish(UUID sessionId, Update update) {
        var group = subscribers.get(sessionId);
        if (group != null) {
            for (Subscriber subscriber : group) {
                if (!subscriber.pending.offer(update)) {
                    subscriber.stop();
                }
            }
        }
    }

    private sealed interface Update permits Signal, Partial {
    }

    private enum Signal implements Update {
        CATCH_UP, ENDED, DELETED
    }

    private record Partial(PartialCaption caption) implements Update {
    }

    private record StatusEvent(SessionStatus status) {
    }

    private final class Subscriber {
        private final UUID sessionId;
        private final SseEmitter emitter;
        private final ArrayBlockingQueue<Update> pending = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Thread worker;
        private long cursor;

        private Subscriber(UUID sessionId, long after, SseEmitter emitter) {
            this.sessionId = sessionId;
            this.cursor = after;
            this.emitter = emitter;
            this.worker = Thread.ofVirtual().name("caption-sse").unstarted(this::run);
        }

        private void run() {
            boolean writeFailed = false;
            try (var ignored = MDC.putCloseable("sessionId", sessionId.toString())) {
                stream();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // The servlet container dispatches the async error for a failed write.
                writeFailed = true;
            } catch (RuntimeException e) {
                LOG.debug("Caption stream closed", e);
            } finally {
                stop();
                if (!writeFailed) {
                    emitter.complete();
                }
            }
        }

        private void stream() throws IOException, InterruptedException {
            SessionStatus initial = sessionStatus();
            if (initial == SessionStatus.LIVE) {
                sendStatus(initial);
            }
            if (initial == null) {
                sendStatus(SessionStatus.ENDED);
                return;
            }
            catchUp();
            if (initial == SessionStatus.ENDED) {
                sendStatus(initial);
                return;
            }

            long heartbeat = System.nanoTime() + HEARTBEAT_NANOS;
            while (!stopped.get()) {
                long remaining = heartbeat - System.nanoTime();
                if (remaining <= 0) {
                    SessionStatus current = sessionStatus();
                    if (current != null) {
                        catchUp();
                    }
                    if (current != SessionStatus.LIVE) {
                        sendStatus(SessionStatus.ENDED);
                        return;
                    }
                    send(SseEmitter.event().name("ping").data("ping"));
                    heartbeat = System.nanoTime() + HEARTBEAT_NANOS;
                    continue;
                }
                switch (pending.poll(remaining, TimeUnit.NANOSECONDS)) {
                    case null -> { }
                    case Signal.CATCH_UP -> catchUp();
                    case Signal.ENDED -> {
                        catchUp();
                        sendStatus(SessionStatus.ENDED);
                        return;
                    }
                    case Signal.DELETED -> {
                        sendStatus(SessionStatus.ENDED);
                        return;
                    }
                    case Partial(var partial) -> {
                        if (partial.seq() - 1 == cursor) {
                            send(SseEmitter.event().name("partial").data(partial));
                        }
                    }
                }
            }
        }

        private SessionStatus sessionStatus() {
            try {
                return sessions.get(sessionId).status();
            } catch (ResponseStatusException e) {
                if (e.getStatusCode().value() == 404) {
                    return null;
                }
                throw e;
            }
        }

        private void catchUp() throws IOException {
            // Every wake-up reads beyond the same cursor, including wake-ups queued during replay.
            while (!stopped.get()) {
                var batch = replay.readAfter(sessionId, cursor, REPLAY_BATCH);
                if (batch.isEmpty()) {
                    return;
                }
                for (CaptionLine line : batch) {
                    if (stopped.get()) {
                        return;
                    }
                    send(SseEmitter.event().name("caption").id(Long.toString(line.getSeq())).data(line));
                    cursor = line.getSeq();
                }
            }
        }

        private void sendStatus(SessionStatus status) throws IOException {
            send(SseEmitter.event().name("status").data(new StatusEvent(status)));
        }

        private void send(SseEmitter.SseEventBuilder event) throws IOException {
            if (!stopped.get()) {
                emitter.send(event);
            }
        }

        private void stop() {
            if (stopped.compareAndSet(false, true)) {
                subscribers.computeIfPresent(sessionId, (id, group) -> {
                    group.remove(this);
                    return group.isEmpty() ? null : group;
                });
                if (Thread.currentThread() != worker) {
                    worker.interrupt();
                }
            }
        }
    }
}

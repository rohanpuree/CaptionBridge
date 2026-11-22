package com.captionbridge;

import com.captionbridge.caption.CaptionService;
import com.captionbridge.caption.PartialCaption;
import com.captionbridge.session.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "captionbridge.stt.provider=mock",
        "spring.datasource.url=jdbc:h2:mem:replay-smoke;DB_CLOSE_DELAY=-1"
})
class CaptionDeliveryIntegrationTest {
    @LocalServerPort int port;
    @Autowired SessionService sessions;
    @Autowired CaptionService captions;
    @Autowired ApplicationEventPublisher events;

    @Test
    @Timeout(45)
    void mockAudioKeepsBothAttendeesInSyncAcrossReconnect() throws Exception {
        var created = sessions.create("Mock recognition stream");
        var id = created.sessionId();
        String stream = "http://localhost:" + port + "/api/sessions/" + id + "/stream";

        try (var client = HttpClient.newHttpClient(); var first = connect(client, stream, null);
             var second = connect(client, stream, null)) {
            assertThat(next(first).data()).contains("LIVE");
            assertThat(next(second).data()).contains("LIVE");
            var host = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/host/" + id
                            + "?token=" + created.hostToken()), new WebSocket.Listener() { })
                    .get(5, TimeUnit.SECONDS);
            try {
                host.sendBinary(ByteBuffer.wrap(new byte[640]), true).get(5, TimeUnit.SECONDS);
                var partial = next(first);
                assertThat(partial.event()).isEqualTo("partial");
                assertThat(partial.id()).isNull();
                long firstSequence = nextFinal(first);
                assertThat(firstSequence).isEqualTo(1L);
                first.close();

                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(captions.readAfter(id, firstSequence, 1)).hasSize(1));

                try (var resumed = connect(client, stream + "?after=0", Long.toString(firstSequence))) {
                    assertThat(next(resumed).data()).contains("LIVE");
                    long replayed = nextFinal(resumed);
                    assertThat(replayed).isEqualTo(2L);
                    long live = nextFinal(resumed);
                    assertThat(live).isEqualTo(3L);
                    sessions.end(id, created.hostToken());

                    var reconnected = new ArrayList<>(List.of(firstSequence, replayed, live));
                    reconnected.addAll(finalsUntilEnded(resumed));
                    var uninterrupted = finalsUntilEnded(second);
                    int total = captions.readAfter(id, 0, 100).size();
                    assertThat(reconnected).containsExactlyElementsOf(LongStream.rangeClosed(1, total).boxed().toList());
                    assertThat(uninterrupted).containsExactlyElementsOf(reconnected);
                }
            } finally {
                host.abort();
            }
        } finally {
            sessions.delete(id, created.hostToken());
        }
    }

    @Test
    @Timeout(45)
    void replayFallsBackToDatabaseAndDrainsConcurrentWritesInOrder() throws Exception {
        var created = sessions.create("Replay smoke");
        UUID id = created.sessionId();
        String base = "http://localhost:" + port + "/api/sessions/" + id;
        for (int i = 1; i <= 270; i++) {
            append(id, i);
        }

        try (var client = HttpClient.newHttpClient(); var pool = Executors.newFixedThreadPool(2)) {
            try (var first = connect(client, base + "/stream?after=0", null);
                 var second = connect(client, base + "/stream?after=0", null)) {
                assertThat(next(first).event()).isEqualTo("status");
                assertThat(next(second).event()).isEqualTo("status");
                for (long seq = 1; seq <= 270; seq++) {
                    assertCaption(next(first), seq);
                    assertCaption(next(second), seq);
                }
                first.close();
                events.publishEvent(new PartialCaption(id, 271, "testing partial"));
                Frame partial = next(second);
                assertThat(partial.event()).isEqualTo("partial");
                assertThat(partial.id()).isNull();
                for (int i = 271; i <= 280; i++) {
                    append(id, i);
                }

                try (var resumed = connect(client, base + "/stream?after=3", "270")) {
                    assertThat(next(resumed).event()).isEqualTo("status");
                    var liveWriter = pool.submit(() -> {
                        for (int i = 281; i <= 320; i++) {
                            append(id, i);
                        }
                    });
                    for (long seq : LongStream.rangeClosed(271, 320).toArray()) {
                        assertCaption(next(resumed), seq);
                        assertCaption(next(second), seq);
                    }
                    liveWriter.get();
                    sessions.end(id, created.hostToken());
                    assertThat(next(resumed).data()).contains("ENDED");
                    assertThat(next(second).data()).contains("ENDED");
                    assertThat(resumed.readLine()).isNull();
                }
            }

            var catchup = client.send(HttpRequest.newBuilder(URI.create(base + "/captions?after=318&limit=2")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(catchup.statusCode()).isEqualTo(200);
            assertThat(catchup.body()).contains("\"seq\":319", "\"seq\":320");
            try (var ended = connect(client, base + "/stream?after=319", null)) {
                assertCaption(next(ended), 320);
                assertThat(next(ended).data()).contains("ENDED");
            }
        } finally {
            sessions.delete(id, created.hostToken());
        }
    }

    private static long nextFinal(BufferedReader reader) throws Exception {
        Frame frame;
        do {
            frame = next(reader);
            assertThat(frame.event()).isNotEqualTo("status");
        } while (!frame.event().equals("caption"));
        return Long.parseLong(frame.id());
    }

    private static List<Long> finalsUntilEnded(BufferedReader reader) throws Exception {
        var finals = new ArrayList<Long>();
        while (true) {
            Frame frame = next(reader);
            if (frame.event().equals("status")) {
                assertThat(frame.data()).contains("ENDED");
                return finals;
            }
            if (frame.event().equals("caption")) {
                finals.add(Long.parseLong(frame.id()));
            }
        }
    }

    private void append(UUID id, int number) {
        events.publishEvent(captions.appendFinal(id, "line " + number));
    }

    private static BufferedReader connect(HttpClient client, String url, String cursor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/event-stream");
        if (cursor != null) {
            request.header("Last-Event-ID", cursor);
        }
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }

    private static void assertCaption(Frame frame, long sequence) {
        assertThat(frame.event()).isEqualTo("caption");
        assertThat(frame.id()).isEqualTo(Long.toString(sequence));
        assertThat(frame.data()).contains("line " + sequence);
    }

    private static Frame next(BufferedReader reader) throws Exception {
        String event = null;
        String id = null;
        String data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank() && event != null) {
                return new Frame(event, id, data);
            }
            if (line.startsWith("event:")) {
                event = line.substring(6).strip();
            } else if (line.startsWith("id:")) {
                id = line.substring(3).strip();
            } else if (line.startsWith("data:")) {
                data = line.substring(5).strip();
            }
        }
        throw new EOFException("Missing SSE frame");
    }

    private record Frame(String event, String id, String data) {
    }
}

package com.captionbridge;

import com.captionbridge.caption.CaptionLine;
import com.captionbridge.caption.CaptionService;
import com.captionbridge.session.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "captionbridge.stt.provider=mock",
        "spring.datasource.url=jdbc:h2:mem:ingest-smoke;DB_CLOSE_DELAY=-1"
})
class AudioIngestIntegrationTest {
    @LocalServerPort int port;
    @Autowired SessionService sessions;
    @Autowired CaptionService captions;

    @Test
    @Timeout(30)
    void hostAudioSocketAuthenticatesAndPersistsCaptions() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var session = sessions.create("Audio smoke");
            var id = session.sessionId();
            var url = "ws://localhost:" + port + "/ws/host/" + id;
            assertHandshakeStatus(client, url, 401);
            assertHandshakeStatus(client, url + "?token=invalid", 403);
            assertHandshakeStatus(client, url + "?token=invalid&token=duplicate", 400);

            var authenticated = url + "?token=" + session.hostToken();
            var host = new Events();
            var socket = connect(client, authenticated, host);
            assertThat(host.messages.poll(5, TimeUnit.SECONDS)).contains("\"status\":\"ready\"");
            var duplicate = new Events();
            connect(client, authenticated, duplicate);
            assertThat(duplicate.messages.poll(5, TimeUnit.SECONDS)).contains("already connected");
            assertThat(duplicate.closeCodes.poll(5, TimeUnit.SECONDS)).isEqualTo(1008);

            socket.sendBinary(ByteBuffer.wrap(new byte[3200]), true).join();
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                    assertThat(captions.readAfter(id, 0, 1)).extracting(CaptionLine::getText)
                            .containsExactly("Welcome to CaptionBridge."));
            assertThat(captions.readAfter(id, 0, 1).getFirst().getSeq()).isEqualTo(1);
            socket.sendBinary(ByteBuffer.wrap(new byte[3]), true).join();
            assertThat(host.messages.poll(5, TimeUnit.SECONDS)).contains("complete 16-bit samples");
            assertThat(host.closeCodes.poll(5, TimeUnit.SECONDS)).isEqualTo(1007);

            await().atMost(Duration.ofSeconds(5)).until(() -> {
                var next = new Events();
                var replacement = connect(client, authenticated, next);
                String status = next.messages.poll(2, TimeUnit.SECONDS);
                if (status != null && status.contains("\"status\":\"ready\"")) {
                    replacement.sendBinary(ByteBuffer.wrap(new byte[32_002]), true).join();
                    assertThat(next.closeCodes.poll(5, TimeUnit.SECONDS)).isEqualTo(1009);
                    return true;
                }
                return false;
            });
            sessions.end(id, session.hostToken());
            assertHandshakeStatus(client, authenticated, 409);
        }
    }

    private static WebSocket connect(HttpClient client, String url, Events events) {
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(4))
                .buildAsync(URI.create(url), events).orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static void assertHandshakeStatus(HttpClient client, String url, int status) {
        assertThatThrownBy(() -> connect(client, url, new Events())).isInstanceOfSatisfying(
                CompletionException.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(WebSocketHandshakeException.class);
                    assertThat(((WebSocketHandshakeException) e.getCause()).getResponse().statusCode())
                            .isEqualTo(status);
                });
    }

    private static class Events implements WebSocket.Listener {
        final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Integer> closeCodes = new LinkedBlockingQueue<>();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messages.add(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCodes.add(statusCode);
            return null;
        }
    }
}

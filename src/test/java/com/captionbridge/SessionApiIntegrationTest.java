package com.captionbridge;

import com.captionbridge.caption.CaptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "captionbridge.stt.provider=mock",
        "captionbridge.public-base-url=https://captions.example.test/events/",
        "spring.datasource.url=jdbc:h2:mem:session-api;DB_CLOSE_DELAY=-1"
})
class SessionApiIntegrationTest {
    @LocalServerPort int port;
    @Autowired CaptionService captions;
    @Autowired ObjectMapper json;

    @Test
    void qrAndProtectedTranscriptLifecycle() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var make = request("/api/sessions").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"API smoke\"}")).build();
            var created = send(client, make);
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(created.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            var body = json.readTree(created.body());
            var id = UUID.fromString(body.get("sessionId").asText());
            String token = body.get("hostToken").asText();
            String join = body.get("joinUrl").asText();
            assertThat(join).isEqualTo("https://captions.example.test/events/join.html?session=" + id);
            String path = "/api/sessions/" + id;

            var info = send(client, request(path).GET().build());
            assertThat(info.body()).contains("API smoke", "LIVE").doesNotContain(token);
            var qr = client.send(request(path + "/qr.png").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(qr.headers().firstValue("Content-Type")).contains("image/png");
            var image = ImageIO.read(new ByteArrayInputStream(qr.body()));
            var binary = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            assertThat(new QRCodeReader().decode(binary).getText()).isEqualTo(join);

            captions.appendFinal(id, "Résumé <speaker> & audience");
            captions.appendFinal(id, "Next line");
            String export = path + "/export?format=";
            assertThat(send(client, request(export + "txt").GET().build()).statusCode()).isEqualTo(401);
            assertThat(send(client, request(export + "srt").header("X-Host-Token", "invalid").GET().build())
                    .statusCode()).isEqualTo(403);
            for (var extension : new String[]{"txt", "srt", "vtt"}) {
                var result = send(client, request(export + extension).header("X-Host-Token", token).GET().build());
                assertThat(result.statusCode()).isEqualTo(200);
                assertThat(result.headers().firstValue("Content-Disposition").orElseThrow()).contains("." + extension);
                assertThat(result.body()).contains("Résumé", "Next line");
                if (extension.equals("vtt")) {
                    assertThat(result.body()).startsWith("WEBVTT\n\n").contains("&lt;speaker&gt; &amp;");
                }
                if (extension.equals("srt")) {
                    assertThat(result.body()).containsPattern("00:00:[0-9]{2},[0-9]{3} --> ");
                }
            }

            for (int i = 0; i < 9; i++) {
                assertThat(send(client, make).statusCode()).isEqualTo(409);
            }
            assertThat(send(client, make).statusCode()).isEqualTo(429);
            assertThat(send(client, request(path + "/end").POST(HttpRequest.BodyPublishers.noBody()).build())
                    .statusCode()).isEqualTo(401);
            assertThat(send(client, request(path + "/end").header("X-Host-Token", "invalid")
                    .POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode()).isEqualTo(403);
            assertThat(send(client, request(path).DELETE().build()).statusCode()).isEqualTo(401);
            assertThat(send(client, request(path).header("X-Host-Token", "invalid").DELETE().build())
                    .statusCode()).isEqualTo(403);
            var socketClosed = new CompletableFuture<Integer>();
            var socketReady = new CompletableFuture<String>();
            var socket = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/host/" + id + "?token=" + token),
                            new WebSocket.Listener() {
                                @Override
                                public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
                                    socketReady.complete(text.toString());
                                    ws.request(1);
                                    return null;
                                }
                                @Override
                                public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                                    socketClosed.complete(code);
                                    return null;
                                }
                            }).get(5, TimeUnit.SECONDS);
            assertThat(socketReady.get(5, TimeUnit.SECONDS)).contains("ready");
            try {
                assertThat(send(client, request(path + "/end").header("X-Host-Token", token)
                        .POST(HttpRequest.BodyPublishers.noBody()).build()).body()).contains("ENDED");
                assertThat(socketClosed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
            } finally {
                socket.abort();
            }
            assertThat(send(client, request(path).header("X-Host-Token", token).DELETE().build()).statusCode())
                    .isEqualTo(204);
            assertThat(send(client, request(path).GET().build()).statusCode()).isEqualTo(404);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(6));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}

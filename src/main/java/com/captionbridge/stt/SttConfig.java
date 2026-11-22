package com.captionbridge.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class SttConfig {
    @Bean
    SpeechToTextProvider speechToTextProvider(
            @Value("${captionbridge.stt.provider:vosk}") String provider,
            @Value("${captionbridge.stt.vosk.model-path}") String modelPath,
            ObjectMapper json) {
        return switch (provider) {
            case "vosk" -> new VoskSpeechToTextProvider(Path.of(modelPath), json);
            case "mock" -> new MockSpeechToTextProvider();
            case "cloud" -> new CloudSttProviderStub();
            default -> throw new IllegalArgumentException(
                    "Unsupported captionbridge.stt.provider '" + provider + "'. Choose vosk, mock, or cloud.");
        };
    }
}

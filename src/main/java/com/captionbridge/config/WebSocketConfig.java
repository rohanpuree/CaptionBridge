package com.captionbridge.config;

import com.captionbridge.ingest.AudioWebSocketHandler;
import com.captionbridge.ingest.HostTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final AudioWebSocketHandler audio;
    private final HostTokenInterceptor hostToken;

    public WebSocketConfig(AudioWebSocketHandler audio, HostTokenInterceptor hostToken) {
        this.audio = audio;
        this.hostToken = hostToken;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(audio, "/ws/host/{id}").addInterceptors(hostToken);
    }
}

package com.captionbridge.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@Component
public class JoinUrl {
    private final URI publicBase;

    public JoinUrl(@Value("${captionbridge.public-base-url:}") String configuredBase) {
        publicBase = configuredBase.isBlank() ? null : URI.create(configuredBase);
        if (publicBase != null && (publicBase.getHost() == null
                || !("http".equalsIgnoreCase(publicBase.getScheme()) || "https".equalsIgnoreCase(publicBase.getScheme()))
                || publicBase.getRawUserInfo() != null || publicBase.getRawQuery() != null
                || publicBase.getRawFragment() != null)) {
            throw new IllegalArgumentException("captionbridge.public-base-url must be an HTTP(S) base URL");
        }
    }

    public String forSession(HttpServletRequest request, UUID id) {
        var builder = publicBase == null
                ? ServletUriComponentsBuilder.fromContextPath(request)
                : UriComponentsBuilder.fromUri(publicBase);
        return builder.pathSegment("join.html").queryParam("session", id).build(true).toUriString();
    }
}

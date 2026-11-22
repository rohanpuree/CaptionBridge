package com.captionbridge.delivery;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Starts sending only after Spring MVC installs the emitter's response writer. */
@Component
class SseStartFilter extends OncePerRequestFilter {
    static final String ON_READY = SseStartFilter.class.getName() + ".onReady";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        if (request.isAsyncStarted() && request.getAttribute(ON_READY) instanceof Runnable start) {
            request.removeAttribute(ON_READY);
            start.run();
        }
    }
}

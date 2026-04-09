package com.zhongbo.mindos.assistant.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RequestTrackingFilter extends OncePerRequestFilter {

    private final InflightRequestTracker tracker;

    public RequestTrackingFilter(InflightRequestTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (shouldSkipTracking(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        tracker.increment();
        try {
            filterChain.doFilter(request, response);
        } finally {
            tracker.decrement();
        }
    }

    private boolean shouldSkipTracking(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return false;
        }
        return uri.startsWith("/health/readiness")
                || uri.startsWith("/admin/");
    }
}


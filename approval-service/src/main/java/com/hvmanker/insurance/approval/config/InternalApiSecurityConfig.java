package com.hvmanker.insurance.approval.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalApiSecurityConfig extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedTokenBytes;

    public InternalApiSecurityConfig(@Value("${INTERNAL_API_TOKEN:}") String internalApiToken) {
        if (internalApiToken == null || internalApiToken.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN must be configured with a non-blank value");
        }
        this.expectedTokenBytes = internalApiToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String presented = authorization.substring(BEARER_PREFIX.length());
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedTokenBytes, presentedBytes)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }
}

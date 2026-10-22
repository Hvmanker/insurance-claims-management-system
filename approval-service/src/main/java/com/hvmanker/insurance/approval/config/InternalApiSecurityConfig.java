package com.hvmanker.insurance.approval.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
public class InternalApiSecurityConfig {
    private static final String BEARER_PREFIX = "Bearer ";

    @Bean
    OncePerRequestFilter internalApiAuthFilter(@Value("${INTERNAL_API_TOKEN:}") String internalApiToken) {
        if (internalApiToken == null || internalApiToken.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_TOKEN must be configured with a non-blank value");
        }
        final byte[] expectedTokenBytes = internalApiToken.trim().getBytes(StandardCharsets.UTF_8);
        return new OncePerRequestFilter() {
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
                String presented = authorization.substring(BEARER_PREFIX.length()).trim();
                byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(expectedTokenBytes, presentedBytes)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
                filterChain.doFilter(request, response);
            }
        };
    }
}

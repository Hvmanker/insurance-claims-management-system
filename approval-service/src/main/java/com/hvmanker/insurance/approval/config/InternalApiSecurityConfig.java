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

@Configuration
public class InternalApiSecurityConfig {
    private static final String BEARER_PREFIX = "Bearer ";

    @Bean
    OncePerRequestFilter internalApiAuthFilter(@Value("${INTERNAL_API_TOKEN:}") String internalApiToken) {
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
                if (internalApiToken == null || internalApiToken.isBlank()) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                String presented = authorization.substring(BEARER_PREFIX.length()).trim();
                if (!presented.equals(internalApiToken)) {
                    if (request.getHeader(HttpHeaders.COOKIE) != null) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                        return;
                    }
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                filterChain.doFilter(request, response);
            }
        };
    }
}

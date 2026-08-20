package com.allog.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class LocalBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AccessTokenService tokenService;

    public LocalBearerAuthenticationFilter(AccessTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        List<String> headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (headers.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (headers.size() != 1) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String token = extractBearerToken(headers.getFirst());
        if (token == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(AllogAuthenticationToken.authenticated(tokenService.verify(token)));
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    private String extractBearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length());
        return token.isBlank() || token.chars().anyMatch(Character::isWhitespace) ? null : token;
    }
}

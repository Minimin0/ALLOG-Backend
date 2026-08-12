package com.allog.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class FirebaseBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthenticationManager authenticationManager;

    public FirebaseBearerAuthenticationFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
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
            reject(response, HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String idToken = extractBearerToken(headers.getFirst());
        if (idToken == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    FirebaseBearerAuthenticationToken.unauthenticated(idToken)
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
        } catch (AuthenticationServiceException exception) {
            SecurityContextHolder.clearContext();
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            reject(response, HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    private String extractBearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length());
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return token;
    }

    private void reject(HttpServletResponse response, int status) {
        response.setStatus(status);
    }
}

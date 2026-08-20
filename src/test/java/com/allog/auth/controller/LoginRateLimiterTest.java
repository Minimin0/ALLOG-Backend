package com.allog.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    @Test
    void limitsFailedLoginsAndSignupsIndependentlyWithoutSleeping() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        Instant startedAt = Instant.parse("2026-08-20T00:00:00Z");
        String client = "198.51.100.10";

        for (int attempt = 0; attempt < LoginRateLimiter.MAX_LOGIN_FAILURES; attempt++) {
            assertTrue(limiter.consumeLogin(client, startedAt));
        }
        assertFalse(limiter.consumeLogin(client, startedAt));
        assertTrue(limiter.consumeLogin(client, startedAt.plus(LoginRateLimiter.WINDOW)));

        for (int attempt = 0; attempt < LoginRateLimiter.MAX_SIGNUP_REQUESTS; attempt++) {
            assertTrue(limiter.consumeSignup(client, startedAt));
        }
        assertFalse(limiter.consumeSignup(client, startedAt));
    }

    @Test
    void trustsRealIpOnlyFromTheLocalReverseProxy() {
        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.setRemoteAddr("127.0.0.1");
        proxied.addHeader("X-Real-IP", "203.0.113.7");
        assertEquals("203.0.113.7", LoginRateLimiter.clientKey(proxied));

        MockHttpServletRequest malformed = new MockHttpServletRequest();
        malformed.setRemoteAddr("127.0.0.1");
        malformed.addHeader("X-Real-IP", "not-an-ip");
        assertEquals("127.0.0.1", LoginRateLimiter.clientKey(malformed));

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.20");
        direct.addHeader("X-Real-IP", "203.0.113.8");
        assertEquals("198.51.100.20", LoginRateLimiter.clientKey(direct));
    }
}

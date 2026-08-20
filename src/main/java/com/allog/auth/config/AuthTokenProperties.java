package com.allog.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@ConfigurationProperties("allog.auth.token")
public record AuthTokenProperties(String secret, Duration ttl) {

    public AuthTokenProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("ALLOG_AUTH_TOKEN_SECRET must be at least 32 bytes");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("ALLOG_AUTH_TOKEN_TTL must be positive");
        }
    }

    byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}

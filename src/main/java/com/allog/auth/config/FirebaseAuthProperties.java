package com.allog.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("allog.auth.firebase")
public record FirebaseAuthProperties(boolean enabled, String projectId) {
}

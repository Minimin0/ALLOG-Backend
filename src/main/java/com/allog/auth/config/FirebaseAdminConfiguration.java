package com.allog.auth.config;

import com.allog.auth.firebase.FirebaseAdminIdTokenVerifier;
import com.allog.auth.firebase.FirebaseIdTokenVerifier;
import com.allog.auth.firebase.FirebaseVerificationException;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FirebaseAuthProperties.class)
public class FirebaseAdminConfiguration {

    @Bean(destroyMethod = "delete")
    @ConditionalOnProperty(name = "allog.auth.firebase.enabled", havingValue = "true")
    FirebaseApp firebaseApp(FirebaseAuthProperties properties) throws IOException {
        if (properties.projectId() == null || properties.projectId().isBlank()) {
            throw new IllegalStateException("FIREBASE_PROJECT_ID is required when Firebase authentication is enabled");
        }

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setProjectId(properties.projectId())
                .build();
        return FirebaseApp.initializeApp(options, "allog-" + UUID.randomUUID());
    }

    @Bean
    @ConditionalOnProperty(name = "allog.auth.firebase.enabled", havingValue = "true")
    FirebaseAuth firebaseAuth(FirebaseApp firebaseApp) {
        return FirebaseAuth.getInstance(firebaseApp);
    }

    @Bean
    @ConditionalOnProperty(name = "allog.auth.firebase.enabled", havingValue = "true")
    FirebaseIdTokenVerifier firebaseAdminIdTokenVerifier(FirebaseAuth firebaseAuth) {
        return new FirebaseAdminIdTokenVerifier(firebaseAuth);
    }

    @Bean
    @ConditionalOnProperty(
            name = "allog.auth.firebase.enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    FirebaseIdTokenVerifier unavailableFirebaseIdTokenVerifier() {
        return idToken -> {
            throw new FirebaseVerificationException(
                    FirebaseVerificationException.Reason.UNAVAILABLE,
                    "Firebase authentication is disabled"
            );
        };
    }
}

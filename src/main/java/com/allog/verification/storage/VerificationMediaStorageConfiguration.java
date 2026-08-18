package com.allog.verification.storage;

import com.allog.verification.storage.local.LocalVerificationMediaStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VerificationMediaProperties.class)
public class VerificationMediaStorageConfiguration {
    @Bean
    VerificationMediaStorage verificationMediaStorage(
            VerificationMediaProperties properties,
            Clock clock
    ) {
        if (!properties.enabled()) {
            return disabledVerificationMediaStorage();
        }
        properties.validateEnabledConfiguration();
        return new LocalVerificationMediaStorage(properties, clock);
    }

    private VerificationMediaStorage disabledVerificationMediaStorage() {
        return new VerificationMediaStorage() {
            @Override
            public UploadGrant issueUpload(
                    String objectKey,
                    String contentType,
                    long sizeBytes,
                    java.time.Instant expiresAt
            ) {
                throw unavailable();
            }

            @Override
            public StoredMediaInspection inspect(String objectKey) {
                throw unavailable();
            }

            private StorageException unavailable() {
                return new StorageException(
                        StorageException.Reason.UNAVAILABLE,
                        "verification media storage is disabled"
                );
            }
        };
    }
}

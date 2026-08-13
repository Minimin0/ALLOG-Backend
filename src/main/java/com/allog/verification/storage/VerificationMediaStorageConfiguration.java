package com.allog.verification.storage;

import com.allog.verification.storage.s3.S3VerificationMediaStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VerificationMediaProperties.class)
public class VerificationMediaStorageConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "allog.verification.media.enabled", havingValue = "true")
    S3Client verificationMediaS3Client(VerificationMediaProperties properties) {
        properties.validateEnabledConfiguration();
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "allog.verification.media.enabled", havingValue = "true")
    S3Presigner verificationMediaS3Presigner(VerificationMediaProperties properties) {
        properties.validateEnabledConfiguration();
        return S3Presigner.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "allog.verification.media.enabled", havingValue = "true")
    VerificationMediaStorage s3VerificationMediaStorage(
            S3Client verificationMediaS3Client,
            S3Presigner verificationMediaS3Presigner,
            VerificationMediaProperties properties,
            Clock clock
    ) {
        return new S3VerificationMediaStorage(
                verificationMediaS3Client,
                verificationMediaS3Presigner,
                properties.bucket(),
                clock
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "allog.verification.media.enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    VerificationMediaStorage disabledVerificationMediaStorage() {
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

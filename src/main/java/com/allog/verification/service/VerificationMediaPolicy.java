package com.allog.verification.service;

import com.allog.verification.storage.VerificationMediaProperties;
import com.allog.verification.storage.VerificationMediaStorage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Component
final class VerificationMediaPolicy {

    private final VerificationMediaProperties properties;

    VerificationMediaPolicy(VerificationMediaProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    void requireEnabled() {
        if (!properties.enabled()) {
            throw new VerificationMediaStorage.StorageException(
                    VerificationMediaStorage.StorageException.Reason.UNAVAILABLE,
                    "verification media storage is disabled"
            );
        }
        properties.validateEnabledConfiguration();
    }

    String requireAllowedContentType(String contentType) {
        final String normalized;
        try {
            normalized = VerificationMediaProperties.normalizeContentType(contentType);
        } catch (IllegalArgumentException exception) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.UNSUPPORTED_CONTENT_TYPE,
                    "verification media content type is unsupported"
            );
        }
        if (!properties.allowedContentTypes().contains(normalized)) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.UNSUPPORTED_CONTENT_TYPE,
                    "verification media content type is unsupported"
            );
        }
        return normalized;
    }

    void requireAllowedSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.INVALID_SIZE,
                    "verification media size must be positive"
            );
        }
        if (sizeBytes > properties.maxBytes()) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.MEDIA_TOO_LARGE,
                    "verification media is too large"
            );
        }
    }

    void requireInspection(
            String expectedObjectKey,
            String expectedContentType,
            long expectedSizeBytes,
            VerificationMediaStorage.StoredMediaInspection inspection
    ) {
        Objects.requireNonNull(inspection, "inspection must not be null");
        if (!expectedObjectKey.equals(inspection.objectKey())) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.BINDING_MISMATCH,
                    "inspected media is not bound to the current verification"
            );
        }
        requireAllowedSize(inspection.contentLength());
        if (expectedSizeBytes != inspection.contentLength()) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.SIZE_MISMATCH,
                    "stored media size does not match the upload intent"
            );
        }
        String actualContentType;
        try {
            actualContentType = VerificationMediaProperties.normalizeContentType(inspection.contentType());
        } catch (IllegalArgumentException exception) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.CONTENT_TYPE_MISMATCH,
                    "stored media content type does not match the upload intent"
            );
        }
        String allowedExpectedType = requireAllowedContentType(expectedContentType);
        if (!allowedExpectedType.equals(actualContentType)) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.CONTENT_TYPE_MISMATCH,
                    "stored media content type does not match the upload intent"
            );
        }
    }

    Duration uploadExpiry() {
        return properties.uploadExpiry();
    }
}

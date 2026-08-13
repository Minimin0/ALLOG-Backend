package com.allog.verification.storage;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface VerificationMediaStorage {

    UploadGrant issueUpload(
            String objectKey,
            String contentType,
            long sizeBytes,
            Instant expiresAt
    );

    StoredMediaInspection inspect(String objectKey);

    record UploadGrant(
            URI uri,
            String method,
            Map<String, List<String>> requiredHeaders,
            Instant expiresAt
    ) {
        public UploadGrant {
            requiredHeaders = requiredHeaders.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT),
                            entry -> List.copyOf(entry.getValue())
                    ));
        }
    }

    record StoredMediaInspection(String objectKey, long contentLength, String contentType) {
    }

    final class StorageException extends RuntimeException {

        public enum Reason {
            NOT_FOUND,
            UNAVAILABLE,
            CONFIGURATION
        }

        private final Reason reason;

        public StorageException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public StorageException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}

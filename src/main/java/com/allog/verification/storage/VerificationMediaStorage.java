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

    default StoredMedia acquire(String objectKey, long maxBytes) {
        throw new StorageException(
                StorageException.Reason.UNAVAILABLE,
                "verification media acquisition is unavailable"
        );
    }

    /**
     * A short-lived link an operator can open to look at the photo they are being asked to judge.
     * Read-only, expiring, and never handed to anyone but an operator.
     */
    default URI issueDownload(String objectKey, Instant expiresAt) {
        throw new StorageException(
                StorageException.Reason.UNAVAILABLE,
                "verification media download is unavailable"
        );
    }

    /**
     * Replaces the bytes stored under an existing key. Only ever writes the key it is given, so the
     * sanitized image supersedes the original instead of being kept alongside it.
     */
    default void overwrite(String objectKey, String contentType, byte[] content) {
        throw new StorageException(
                StorageException.Reason.UNAVAILABLE,
                "verification media overwrite is unavailable"
        );
    }

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

    record StoredMedia(String objectKey, long contentLength, String contentType, byte[] content) {

        public StoredMedia {
            content = java.util.Objects.requireNonNull(content, "content must not be null").clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public int bodyLength() {
            return content.length;
        }
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

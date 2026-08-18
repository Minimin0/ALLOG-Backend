package com.allog.verification.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.http.MediaType;

@ConfigurationProperties("allog.verification.media")
public record VerificationMediaProperties(
        boolean enabled,
        long maxBytes,
        Duration uploadExpiry,
        Set<String> allowedContentTypes,
        String localRoot,
        String localBaseUrl,
        String localSigningSecret
) {
    private static final Duration MAXIMUM_UPLOAD_EXPIRY = Duration.ofDays(7);
    private static final int MINIMUM_LOCAL_SIGNING_SECRET_BYTES = 32;

    @ConstructorBinding
    public VerificationMediaProperties {
        uploadExpiry = uploadExpiry == null ? Duration.ZERO : uploadExpiry;
        allowedContentTypes = allowedContentTypes == null
                ? Set.of()
                : allowedContentTypes.stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        localRoot = localRoot == null ? "" : localRoot.trim();
        localBaseUrl = localBaseUrl == null ? "" : localBaseUrl.trim();
        localSigningSecret = localSigningSecret == null ? "" : localSigningSecret.trim();
    }

    public void validateEnabledConfiguration() {
        if (!enabled) {
            return;
        }
        if (maxBytes <= 0) {
            throw new IllegalStateException(
                    "verification media maxBytes must be positive when enabled"
            );
        }
        if (uploadExpiry.isZero() || uploadExpiry.isNegative()
                || uploadExpiry.compareTo(MAXIMUM_UPLOAD_EXPIRY) > 0) {
            throw new IllegalStateException(
                    "verification media uploadExpiry must be positive and at most 7 days"
            );
        }
        if (allowedContentTypes.isEmpty()) {
            throw new IllegalStateException(
                    "verification media allowedContentTypes are required when enabled"
            );
        }
        try {
            allowedContentTypes.forEach(VerificationMediaProperties::normalizeContentType);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "verification media allowedContentTypes must contain only explicit media types",
                    exception
            );
        }
        validateLocalConfiguration();
    }

    private void validateLocalConfiguration() {
        if (localRoot.isBlank()) {
            throw new IllegalStateException(
                    "verification media localRoot is required when enabled"
            );
        }
        try {
            URI uri = URI.create(localBaseUrl);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("base URL must be absolute HTTPS");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "verification media localBaseUrl must be an absolute URI",
                    exception
            );
        }
        int secretBytes = localSigningSecret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < MINIMUM_LOCAL_SIGNING_SECRET_BYTES) {
            throw new IllegalStateException(
                    "verification media localSigningSecret must be at least 32 bytes"
            );
        }
    }

    public static String normalizeContentType(String value) {
        MediaType mediaType = MediaType.parseMediaType(
                value == null ? "" : value.trim()
        );
        if (mediaType.isWildcardType()
                || mediaType.isWildcardSubtype()
                || !mediaType.getParameters().isEmpty()) {
            throw new IllegalArgumentException(
                    "content type must be explicit and must not have parameters"
            );
        }
        return (mediaType.getType() + "/" + mediaType.getSubtype())
                .toLowerCase(Locale.ROOT);
    }
}

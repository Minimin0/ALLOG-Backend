package com.allog.verification.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties("allog.verification.media")
public record VerificationMediaProperties(
        boolean enabled,
        String bucket,
        String region,
        long maxBytes,
        Duration uploadExpiry,
        Set<String> allowedContentTypes
) {

    private static final Duration MAXIMUM_PRESIGN_EXPIRY = Duration.ofDays(7);

    public VerificationMediaProperties {
        bucket = bucket == null ? "" : bucket.trim();
        region = region == null ? "" : region.trim();
        uploadExpiry = uploadExpiry == null ? Duration.ZERO : uploadExpiry;
        allowedContentTypes = allowedContentTypes == null
                ? Set.of()
                : allowedContentTypes.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validateEnabledConfiguration() {
        if (!enabled) {
            return;
        }
        if (bucket.isBlank()) {
            throw new IllegalStateException("verification media bucket is required when enabled");
        }
        if (region.isBlank()) {
            throw new IllegalStateException("verification media region is required when enabled");
        }
        if (maxBytes <= 0) {
            throw new IllegalStateException("verification media maxBytes must be positive when enabled");
        }
        if (uploadExpiry.isZero() || uploadExpiry.isNegative()
                || uploadExpiry.compareTo(MAXIMUM_PRESIGN_EXPIRY) > 0) {
            throw new IllegalStateException("verification media uploadExpiry must be positive and at most 7 days");
        }
        if (allowedContentTypes.isEmpty()) {
            throw new IllegalStateException("verification media allowedContentTypes are required when enabled");
        }
        try {
            allowedContentTypes.forEach(VerificationMediaProperties::normalizeContentType);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "verification media allowedContentTypes must contain only explicit media types",
                    exception
            );
        }
    }

    public static String normalizeContentType(String value) {
        MediaType mediaType = MediaType.parseMediaType(value == null ? "" : value.trim());
        if (mediaType.isWildcardType() || mediaType.isWildcardSubtype() || !mediaType.getParameters().isEmpty()) {
            throw new IllegalArgumentException("content type must be explicit and must not have parameters");
        }
        return (mediaType.getType() + "/" + mediaType.getSubtype()).toLowerCase(Locale.ROOT);
    }
}

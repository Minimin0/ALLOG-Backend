package com.allog.verification.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerificationMediaPropertiesTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsExplicitEnabledLocalConfiguration() {
        VerificationMediaProperties properties = properties(Set.of("VIDEO/MP4", "image/jpeg"));

        assertDoesNotThrow(properties::validateEnabledConfiguration);
        assertEquals(Set.of("video/mp4", "image/jpeg"), properties.allowedContentTypes());
    }

    @Test
    void rejectsWildcardAndMissingEnabledConfiguration() {
        assertThrows(
                IllegalStateException.class,
                () -> properties(Set.of("video/*")).validateEnabledConfiguration()
        );
        assertThrows(
                IllegalStateException.class,
                () -> new VerificationMediaProperties(
                        true,
                        0,
                        Duration.ZERO,
                        Set.of(),
                        "",
                        "",
                        ""
                ).validateEnabledConfiguration()
        );
    }

    @Test
    void disabledConfigurationNeedsNoStorageValues() {
        VerificationMediaProperties properties = new VerificationMediaProperties(
                false,
                0,
                Duration.ZERO,
                Set.of(),
                "",
                "",
                ""
        );

        assertDoesNotThrow(properties::validateEnabledConfiguration);
    }

    private VerificationMediaProperties properties(Set<String> allowedTypes) {
        return new VerificationMediaProperties(
                true,
                1_000_000,
                Duration.ofMinutes(5),
                allowedTypes,
                "/tmp/allog-verification-media-test",
                "https://api.allog-app.store",
                SECRET
        );
    }
}

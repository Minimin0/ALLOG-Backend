package com.allog.verification.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerificationMediaPropertiesTest {

    @Test
    void acceptsExplicitEnabledConfiguration() {
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
                () -> new VerificationMediaProperties(true, "", "", 0, Duration.ZERO, Set.of())
                        .validateEnabledConfiguration()
        );
    }

    @Test
    void disabledConfigurationNeedsNoAwsValues() {
        VerificationMediaProperties properties = new VerificationMediaProperties(
                false,
                "",
                "",
                0,
                Duration.ZERO,
                Set.of()
        );

        assertDoesNotThrow(properties::validateEnabledConfiguration);
    }

    private VerificationMediaProperties properties(Set<String> allowedTypes) {
        return new VerificationMediaProperties(
                true,
                "test-bucket",
                "ap-northeast-2",
                1_000_000,
                Duration.ofMinutes(5),
                allowedTypes
        );
    }
}

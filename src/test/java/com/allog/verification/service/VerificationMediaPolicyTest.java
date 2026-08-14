package com.allog.verification.service;

import com.allog.verification.storage.VerificationMediaProperties;
import com.allog.verification.storage.VerificationMediaStorage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerificationMediaPolicyTest {

    private final VerificationMediaPolicy policy = new VerificationMediaPolicy(
            new VerificationMediaProperties(
                    true,
                    "test-bucket",
                    "ap-northeast-2",
                    100,
                    Duration.ofMinutes(5),
                    Set.of("video/mp4", "image/jpeg")
            )
    );

    @Test
    void acceptsOnlyConfiguredExplicitTypeAndBoundSize() {
        assertEquals("video/mp4", policy.requireAllowedContentType("VIDEO/MP4"));
        assertDoesNotThrow(() -> policy.requireAllowedSize(100));
        assertDoesNotThrow(() -> policy.requireInspection(
                "verification-media/test",
                "video/mp4",
                100,
                new VerificationMediaStorage.StoredMediaInspection(
                        "verification-media/test", 100, "video/mp4"
                )
        ));
    }

    @Test
    void rejectsUnsupportedTypeAndOversize() {
        VerificationMediaCommandException unsupported = assertThrows(
                VerificationMediaCommandException.class,
                () -> policy.requireAllowedContentType("video/quicktime")
        );
        VerificationMediaCommandException oversized = assertThrows(
                VerificationMediaCommandException.class,
                () -> policy.requireAllowedSize(101)
        );

        assertEquals(VerificationMediaCommandException.Reason.UNSUPPORTED_CONTENT_TYPE, unsupported.reason());
        assertEquals(VerificationMediaCommandException.Reason.MEDIA_TOO_LARGE, oversized.reason());
    }

    @Test
    void rejectsInspectionBindingSizeAndTypeMismatches() {
        assertReason(
                VerificationMediaCommandException.Reason.BINDING_MISMATCH,
                new VerificationMediaStorage.StoredMediaInspection("verification-media/other", 100, "video/mp4")
        );
        assertReason(
                VerificationMediaCommandException.Reason.SIZE_MISMATCH,
                new VerificationMediaStorage.StoredMediaInspection("verification-media/test", 99, "video/mp4")
        );
        assertReason(
                VerificationMediaCommandException.Reason.CONTENT_TYPE_MISMATCH,
                new VerificationMediaStorage.StoredMediaInspection("verification-media/test", 100, "image/jpeg")
        );
    }

    private void assertReason(
            VerificationMediaCommandException.Reason reason,
            VerificationMediaStorage.StoredMediaInspection inspection
    ) {
        VerificationMediaCommandException exception = assertThrows(
                VerificationMediaCommandException.class,
                () -> policy.requireInspection(
                        "verification-media/test",
                        "video/mp4",
                        100,
                        inspection
                )
        );
        assertEquals(reason, exception.reason());
    }
}

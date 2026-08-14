package com.allog.verification.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class VerificationMediaTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-08-13T10:00:00.123456Z");

    @Test
    void confirmsWithBackendClockAndIsIdempotentForSameSize() {
        VerificationMedia media = media();
        Clock firstClock = Clock.fixed(CONFIRMED_AT, ZoneOffset.UTC);
        Clock laterClock = Clock.fixed(CONFIRMED_AT.plusSeconds(1), ZoneOffset.UTC);

        media.confirm(100, firstClock);
        media.confirm(100, laterClock);

        assertAll(
                () -> assertTrue(media.isConfirmed()),
                () -> assertEquals(100L, media.getConfirmedSizeBytes()),
                () -> assertEquals(CONFIRMED_AT, media.getConfirmedAt())
        );
    }

    @Test
    void rejectsInvalidOrDifferentConfirmationMetadata() {
        VerificationMedia media = media();

        assertThrows(IllegalArgumentException.class, () -> media.confirm(0, Clock.systemUTC()));
        assertThrows(IllegalStateException.class, () -> media.confirm(101, Clock.systemUTC()));
        assertThrows(NullPointerException.class, () -> media.confirm(100, null));
        assertFalse(media.isConfirmed());

        media.confirm(100, Clock.fixed(CONFIRMED_AT, ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, () -> media.confirm(99, Clock.systemUTC()));
    }

    /** In-place EXIF sanitization shrinks the stored object, so a smaller confirmation is legitimate. */
    @Test
    void confirmsASanitizedObjectSmallerThanTheUploadIntent() {
        VerificationMedia media = media();

        media.confirm(64, Clock.fixed(CONFIRMED_AT, ZoneOffset.UTC));

        assertAll(
                () -> assertTrue(media.isConfirmed()),
                () -> assertEquals(64L, media.getConfirmedSizeBytes()),
                () -> assertEquals(100L, media.getExpectedSizeBytes())
        );
    }

    private VerificationMedia media() {
        Verification verification = Verification.create(
                mock(com.allog.group.domain.GroupMember.class),
                mock(com.allog.routine.domain.RoutineSchedule.class),
                LocalDate.of(2026, 8, 13)
        );
        return VerificationMedia.create(
                verification,
                "verification-media/test",
                "video/mp4",
                100
        );
    }
}

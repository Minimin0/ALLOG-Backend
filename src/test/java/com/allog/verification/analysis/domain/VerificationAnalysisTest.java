package com.allog.verification.analysis.domain;

import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerificationAnalysisTest {

    private static final Instant FIRST_ATTEMPT = Instant.parse("2026-08-14T00:00:00Z");

    @Test
    void createsPendingAnalysisForSubmittedVerification() {
        Verification verification = verification(VerificationStatus.SUBMITTED);
        UUID requestId = UUID.randomUUID();

        VerificationAnalysis analysis = VerificationAnalysis.createPending(verification, requestId);

        assertAll(
                () -> assertSame(verification, analysis.getVerification()),
                () -> assertEquals(requestId, analysis.getAnalysisRequestId()),
                () -> assertEquals(VerificationAnalysisStatus.PENDING, analysis.getStatus()),
                () -> assertEquals(0, analysis.getAttemptCount()),
                () -> assertNull(analysis.getRecommendation()),
                () -> assertNull(analysis.getFailureCode()),
                () -> assertNull(analysis.getCompletedAt()),
                () -> assertNull(analysis.getObjectPresence()),
                () -> assertNull(analysis.getRelevanceScore()),
                () -> assertNull(analysis.getAnomalyDetected()),
                () -> assertNull(analysis.getFramedProperly())
        );
    }

    @Test
    void requiresVerificationAndBackendRequestId() {
        Verification submitted = verification(VerificationStatus.SUBMITTED);

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> VerificationAnalysis.createPending(null, UUID.randomUUID())
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> VerificationAnalysis.createPending(submitted, null)
                )
        );
    }

    @Test
    void rejectsAnalysisBeforeSubmission() {
        Verification pending = verification(VerificationStatus.PENDING_UPLOAD);

        assertThrows(
                IllegalStateException.class,
                () -> VerificationAnalysis.createPending(pending, UUID.randomUUID())
        );
    }

    @Test
    void startsOneProcessingAttemptWithCallerTimestamp() {
        VerificationAnalysis analysis = VerificationAnalysis.createPending(
                verification(VerificationStatus.SUBMITTED),
                UUID.randomUUID()
        );

        analysis.startProcessing(FIRST_ATTEMPT);

        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, analysis.getStatus()),
                () -> assertEquals(1, analysis.getAttemptCount()),
                () -> assertEquals(FIRST_ATTEMPT, analysis.getLastAttemptAt()),
                () -> assertNull(analysis.getCompletedAt()),
                () -> assertNull(analysis.getRecommendation()),
                () -> assertNull(analysis.getFailureCode())
        );
    }

    @Test
    void rejectsInvalidProcessingTransitionsWithoutPartialMutation() {
        VerificationAnalysis analysis = VerificationAnalysis.createPending(
                verification(VerificationStatus.SUBMITTED),
                UUID.randomUUID()
        );

        assertThrows(NullPointerException.class, () -> analysis.startProcessing(null));
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PENDING, analysis.getStatus()),
                () -> assertEquals(0, analysis.getAttemptCount()),
                () -> assertNull(analysis.getLastAttemptAt())
        );

        analysis.startProcessing(FIRST_ATTEMPT);
        assertThrows(IllegalStateException.class, () -> analysis.startProcessing(FIRST_ATTEMPT.plusSeconds(1)));
        assertThrows(
                IllegalStateException.class,
                () -> analysis.recoverForRetry(FIRST_ATTEMPT.minusNanos(1))
        );
    }

    @Test
    void staleBoundaryIsInclusiveAndRecoveryDoesNotCountAnAttempt() {
        VerificationAnalysis analysis = VerificationAnalysis.createPending(
                verification(VerificationStatus.SUBMITTED),
                UUID.randomUUID()
        );
        analysis.startProcessing(FIRST_ATTEMPT);

        assertAll(
                () -> assertFalse(analysis.isRetryEligible(FIRST_ATTEMPT.minusNanos(1))),
                () -> assertTrue(analysis.isRetryEligible(FIRST_ATTEMPT))
        );

        analysis.recoverForRetry(FIRST_ATTEMPT);
        assertThrows(
                IllegalStateException.class,
                () -> analysis.startProcessing(FIRST_ATTEMPT.minusNanos(1))
        );
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PENDING, analysis.getStatus()),
                () -> assertEquals(1, analysis.getAttemptCount()),
                () -> assertEquals(FIRST_ATTEMPT, analysis.getLastAttemptAt()),
                () -> assertTrue(analysis.isRetryEligible(FIRST_ATTEMPT.plus(Duration.ofMinutes(5))))
        );

        analysis.startProcessing(FIRST_ATTEMPT.plus(Duration.ofMinutes(5)));
        assertEquals(2, analysis.getAttemptCount());
    }

    private Verification verification(VerificationStatus status) {
        Verification verification = mock(Verification.class);
        when(verification.getStatus()).thenReturn(status);
        return verification;
    }
}

package com.allog.verification.analysis.domain;

import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

    @Test
    void succeedsActiveAttemptWithTerminalInvariant() {
        VerificationAnalysis analysis = processingAnalysis();
        Instant completedAt = FIRST_ATTEMPT.plusSeconds(30);

        analysis.succeed(
                AnalysisRecommendation.REVIEW_REQUIRED,
                "synthetic-reason",
                "synthetic-model",
                "synthetic-criteria",
                true,
                new BigDecimal("0.7500"),
                false,
                true,
                completedAt
        );

        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.SUCCEEDED, analysis.getStatus()),
                () -> assertEquals(AnalysisRecommendation.REVIEW_REQUIRED, analysis.getRecommendation()),
                () -> assertEquals("synthetic-reason", analysis.getReasonCode()),
                () -> assertEquals("synthetic-model", analysis.getProviderModel()),
                () -> assertEquals("synthetic-criteria", analysis.getCriteriaVersion()),
                () -> assertEquals(true, analysis.getObjectPresence()),
                () -> assertEquals(new BigDecimal("0.7500"), analysis.getRelevanceScore()),
                () -> assertEquals(false, analysis.getAnomalyDetected()),
                () -> assertEquals(true, analysis.getFramedProperly()),
                () -> assertNull(analysis.getFailureCode()),
                () -> assertEquals(completedAt, analysis.getCompletedAt()),
                () -> assertEquals(1, analysis.getAttemptCount()),
                () -> assertEquals(FIRST_ATTEMPT, analysis.getLastAttemptAt())
        );
    }

    @Test
    void failsActiveAttemptWithTerminalInvariant() {
        VerificationAnalysis analysis = processingAnalysis();
        Instant completedAt = FIRST_ATTEMPT.plusSeconds(30);

        analysis.fail(VerificationAnalysisFailureCode.TIMEOUT, completedAt);

        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.FAILED, analysis.getStatus()),
                () -> assertEquals(VerificationAnalysisFailureCode.TIMEOUT, analysis.getFailureCode()),
                () -> assertNull(analysis.getRecommendation()),
                () -> assertEquals(completedAt, analysis.getCompletedAt()),
                () -> assertEquals(1, analysis.getAttemptCount()),
                () -> assertEquals(FIRST_ATTEMPT, analysis.getLastAttemptAt())
        );
    }

    @Test
    void rejectsInvalidCompletionWithoutPartialMutation() {
        VerificationAnalysis pending = VerificationAnalysis.createPending(
                verification(VerificationStatus.SUBMITTED),
                UUID.randomUUID()
        );
        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> pending.succeed(
                        AnalysisRecommendation.PASS, null, null, null, null, null, null, null, FIRST_ATTEMPT
                )),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> pending.fail(VerificationAnalysisFailureCode.TIMEOUT, FIRST_ATTEMPT)
                )
        );

        VerificationAnalysis processing = processingAnalysis();
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> processing.succeed(
                        null, null, null, null, null, null, null, null, FIRST_ATTEMPT
                )),
                () -> assertThrows(IllegalArgumentException.class, () -> processing.succeed(
                        AnalysisRecommendation.PASS,
                        null,
                        null,
                        null,
                        null,
                        new BigDecimal("1.0001"),
                        null,
                        null,
                        FIRST_ATTEMPT
                )),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> processing.fail(null, FIRST_ATTEMPT)
                ),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> processing.fail(VerificationAnalysisFailureCode.TIMEOUT, FIRST_ATTEMPT.minusNanos(1))
                ),
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, processing.getStatus()),
                () -> assertNull(processing.getRecommendation()),
                () -> assertNull(processing.getFailureCode()),
                () -> assertNull(processing.getCompletedAt())
        );
    }

    @Test
    void terminalAnalysisCannotBeCompletedAgain() {
        VerificationAnalysis succeeded = processingAnalysis();
        succeeded.succeed(
                AnalysisRecommendation.PASS,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                FIRST_ATTEMPT
        );
        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> succeeded.succeed(
                        AnalysisRecommendation.REVIEW_REQUIRED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        FIRST_ATTEMPT.plusSeconds(1)
                )),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> succeeded.fail(VerificationAnalysisFailureCode.TIMEOUT, FIRST_ATTEMPT.plusSeconds(1))
                )
        );

        VerificationAnalysis failed = processingAnalysis();
        failed.fail(VerificationAnalysisFailureCode.TIMEOUT, FIRST_ATTEMPT);
        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> failed.succeed(
                        AnalysisRecommendation.PASS,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        FIRST_ATTEMPT.plusSeconds(1)
                )),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> failed.fail(VerificationAnalysisFailureCode.NETWORK, FIRST_ATTEMPT.plusSeconds(1))
                )
        );
    }

    private VerificationAnalysis processingAnalysis() {
        VerificationAnalysis analysis = VerificationAnalysis.createPending(
                verification(VerificationStatus.SUBMITTED),
                UUID.randomUUID()
        );
        analysis.startProcessing(FIRST_ATTEMPT);
        return analysis;
    }

    private Verification verification(VerificationStatus status) {
        Verification verification = mock(Verification.class);
        when(verification.getStatus()).thenReturn(status);
        return verification;
    }
}

package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.analysis.domain.VerificationAnalysis;
import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationAnalysisStatus;
import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.analysis.repository.VerificationAnalysisRepository;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationAnalysisResultServiceTest {

    private static final Long ANALYSIS_ID = 100L;
    private static final Instant ATTEMPT_STARTED_AT = Instant.parse("2026-08-14T00:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-14T00:00:30.123456789Z");

    @Mock
    private VerificationAnalysisRepository repository;

    private CountingClock clock;
    private VerificationAnalysisResultService service;

    @BeforeEach
    void setUp() {
        clock = new CountingClock(COMPLETED_AT);
        service = new VerificationAnalysisResultService(repository, clock);
    }

    @Test
    void completesSuccessOnlyForCurrentProcessingAttempt() {
        Fixture fixture = processingFixture();
        when(repository.findByIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(fixture.analysis()));

        assertTrue(service.completeSuccess(fixture.claim(), successResult(AnalysisRecommendation.PASS)));

        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.SUCCEEDED, fixture.analysis().getStatus()),
                () -> assertEquals(AnalysisRecommendation.PASS, fixture.analysis().getRecommendation()),
                () -> assertEquals("OBSERVATION_COMPLETE", fixture.analysis().getReasonCode()),
                () -> assertEquals("synthetic-model", fixture.analysis().getProviderModel()),
                () -> assertEquals("TEST_EVIDENCE@1", fixture.analysis().getCriteriaVersion()),
                () -> assertEquals(Instant.parse("2026-08-14T00:00:30.123456Z"), fixture.analysis().getCompletedAt()),
                () -> assertEquals(1, fixture.analysis().getAttemptCount()),
                () -> assertEquals(ATTEMPT_STARTED_AT, fixture.analysis().getLastAttemptAt()),
                () -> assertEquals(1, clock.reads())
        );
    }

    @Test
    void completesFailureOnlyForCurrentProcessingAttempt() {
        Fixture fixture = processingFixture();
        when(repository.findByIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(fixture.analysis()));

        assertTrue(service.completeFailure(fixture.claim(), VerificationAnalysisFailureCode.TIMEOUT));

        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.FAILED, fixture.analysis().getStatus()),
                () -> assertEquals(VerificationAnalysisFailureCode.TIMEOUT, fixture.analysis().getFailureCode()),
                () -> assertNull(fixture.analysis().getRecommendation()),
                () -> assertEquals(Instant.parse("2026-08-14T00:00:30.123456Z"), fixture.analysis().getCompletedAt()),
                () -> assertEquals(1, fixture.analysis().getAttemptCount()),
                () -> assertEquals(ATTEMPT_STARTED_AT, fixture.analysis().getLastAttemptAt()),
                () -> assertEquals(1, clock.reads())
        );
    }

    @Test
    void rejectsWrongAttemptForSuccessAndFailureWithoutReadingClock() {
        Fixture fixture = processingFixture();
        VerificationAnalysisClaim staleClaim = new VerificationAnalysisClaim(
                ANALYSIS_ID,
                fixture.claim().analysisRequestId(),
                fixture.claim().attemptCount() + 1
        );
        when(repository.findByIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(fixture.analysis()));

        assertAll(
                () -> assertFalse(service.completeSuccess(staleClaim, successResult(AnalysisRecommendation.PASS))),
                () -> assertFalse(service.completeFailure(staleClaim, VerificationAnalysisFailureCode.TIMEOUT)),
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, fixture.analysis().getStatus()),
                () -> assertNull(fixture.analysis().getRecommendation()),
                () -> assertNull(fixture.analysis().getFailureCode()),
                () -> assertNull(fixture.analysis().getCompletedAt()),
                () -> assertEquals(0, clock.reads())
        );
    }

    @Test
    void rejectsPendingAndMismatchedAnalysisRequest() {
        UUID requestId = UUID.randomUUID();
        VerificationAnalysis pending = pendingAnalysis(requestId);
        VerificationAnalysisClaim claim = new VerificationAnalysisClaim(ANALYSIS_ID, requestId, 1);
        when(repository.findByIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(pending));

        assertFalse(service.completeSuccess(claim, successResult(AnalysisRecommendation.PASS)));

        pending.startProcessing(ATTEMPT_STARTED_AT);
        VerificationAnalysisClaim mixedClaim = new VerificationAnalysisClaim(
                ANALYSIS_ID,
                UUID.randomUUID(),
                1
        );
        assertAll(
                () -> assertFalse(service.completeFailure(mixedClaim, VerificationAnalysisFailureCode.TIMEOUT)),
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, pending.getStatus()),
                () -> assertEquals(0, clock.reads())
        );
    }

    @Test
    void duplicateAndTerminalCompletionAreRejected() {
        Fixture succeeded = processingFixture();
        when(repository.findByIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(succeeded.analysis()));
        assertTrue(service.completeSuccess(succeeded.claim(), successResult(AnalysisRecommendation.PASS)));
        clock.resetReads();
        assertAll(
                () -> assertFalse(service.completeSuccess(
                        succeeded.claim(),
                        successResult(AnalysisRecommendation.PASS)
                )),
                () -> assertFalse(service.completeFailure(
                        succeeded.claim(),
                        VerificationAnalysisFailureCode.TIMEOUT
                )),
                () -> assertEquals(VerificationAnalysisStatus.SUCCEEDED, succeeded.analysis().getStatus()),
                () -> assertEquals(AnalysisRecommendation.PASS, succeeded.analysis().getRecommendation()),
                () -> assertEquals(0, clock.reads())
        );

        Fixture failed = processingFixture();
        when(repository.findByIdForUpdate(ANALYSIS_ID)).thenReturn(Optional.of(failed.analysis()));
        assertTrue(service.completeFailure(failed.claim(), VerificationAnalysisFailureCode.NETWORK));
        clock.resetReads();
        assertAll(
                () -> assertFalse(service.completeSuccess(
                        failed.claim(),
                        successResult(AnalysisRecommendation.REVIEW_REQUIRED)
                )),
                () -> assertFalse(service.completeFailure(
                        failed.claim(),
                        VerificationAnalysisFailureCode.TIMEOUT
                )),
                () -> assertEquals(VerificationAnalysisStatus.FAILED, failed.analysis().getStatus()),
                () -> assertEquals(VerificationAnalysisFailureCode.NETWORK, failed.analysis().getFailureCode()),
                () -> assertEquals(0, clock.reads())
        );
    }

    private Fixture processingFixture() {
        UUID requestId = UUID.randomUUID();
        VerificationAnalysis analysis = pendingAnalysis(requestId);
        analysis.startProcessing(ATTEMPT_STARTED_AT);
        return new Fixture(
                analysis,
                new VerificationAnalysisClaim(ANALYSIS_ID, requestId, analysis.getAttemptCount())
        );
    }

    private VerificationAnalysis pendingAnalysis(UUID requestId) {
        Verification verification = mock(Verification.class);
        when(verification.getStatus()).thenReturn(VerificationStatus.SUBMITTED);
        return VerificationAnalysis.createPending(verification, requestId);
    }

    private VerificationAnalysisSuccessResult successResult(AnalysisRecommendation recommendation) {
        return new VerificationAnalysisSuccessResult(
                recommendation,
                new VerificationCriteria.Reference("TEST_EVIDENCE", 1),
                new VerificationAnalysisProvider.Result(
                        "synthetic-model",
                        new VerificationAnalysisObservation(
                                true,
                                new BigDecimal("0.7500"),
                                false,
                                true,
                                VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
                        )
                )
        );
    }

    private record Fixture(VerificationAnalysis analysis, VerificationAnalysisClaim claim) {
    }

    private static final class CountingClock extends Clock {

        private final Instant instant;
        private final AtomicInteger reads = new AtomicInteger();

        private CountingClock(Instant instant) {
            this.instant = instant;
        }

        int reads() {
            return reads.get();
        }

        void resetReads() {
            reads.set(0);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            reads.incrementAndGet();
            return instant;
        }
    }
}

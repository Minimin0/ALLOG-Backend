package com.allog.verification.analysis;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysis;
import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationAnalysisStatus;
import com.allog.verification.analysis.repository.VerificationAnalysisRepository;
import com.allog.verification.analysis.service.VerificationAnalysisClaim;
import com.allog.verification.analysis.service.VerificationAnalysisClaimService;
import com.allog.verification.analysis.service.VerificationAnalysisResultService;
import com.allog.verification.analysis.service.VerificationAnalysisSuccessResult;
import com.allog.verification.domain.Verification;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "allog.verification.analysis.processing-timeout=PT5M")
@ActiveProfiles("test")
class VerificationAnalysisPersistenceTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-11T10:01:00Z");
    private static final Instant WORKER_NOW = Instant.parse("2026-08-14T00:00:00Z");

    @Autowired
    private VerificationAnalysisRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private VerificationAnalysisClaimService claimService;

    @Autowired
    private VerificationAnalysisResultService resultService;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetWorkerState() {
        jdbcTemplate.update("delete from verification_analysis");
        clock.set(WORKER_NOW);
        clock.resetReads();
    }

    @Test
    void v6PersistsOnePendingAnalysisWithBackendUuid() {
        UUID requestId = UUID.randomUUID();

        VerificationAnalysis stored = inTransaction(() -> {
            Verification verification = persistSubmittedVerification();
            VerificationAnalysis analysis = VerificationAnalysis.createPending(verification, requestId);
            repository.saveAndFlush(analysis);
            return analysis;
        });

        VerificationAnalysis found = repository.findByVerification_Id(stored.getVerification().getId()).orElseThrow();
        assertAll(
                () -> assertNotNull(found.getId()),
                () -> assertEquals(requestId, found.getAnalysisRequestId()),
                () -> assertEquals(VerificationAnalysisStatus.PENDING, found.getStatus()),
                () -> assertEquals(0, found.getAttemptCount()),
                () -> assertNull(found.getRecommendation()),
                () -> assertNull(found.getFailureCode()),
                () -> assertNull(found.getCompletedAt()),
                () -> assertNotNull(found.getCreatedAt()),
                () -> assertNotNull(found.getUpdatedAt()),
                () -> assertEquals(36, jdbcTemplate.queryForObject(
                        "select char_length(analysis_request_id) from verification_analysis where id = ?",
                        Integer.class,
                        found.getId()
                ))
        );
    }

    @Test
    void v6ConstraintsEnforceIdentityEnumsRangesAndTerminalStates() {
        Long firstVerificationId = submittedVerificationId();
        Long secondVerificationId = submittedVerificationId();
        UUID firstRequestId = UUID.randomUUID();
        insertAnalysis(firstVerificationId, firstRequestId, "PENDING", null, null, 0, null, null);

        assertAll(
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        firstVerificationId, UUID.randomUUID(), "PENDING", null, null, 0, null, null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, firstRequestId, "PENDING", null, null, 0, null, null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "UNKNOWN", null, null, 0, null, null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "SUCCEEDED", "UNKNOWN", null, 0, null, COMPLETED_AT
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "FAILED", null, "UNKNOWN", 0, null, COMPLETED_AT
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "PENDING", null, null, -1, null, null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "PENDING", null, null, 0, new BigDecimal("-0.0001"), null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "PENDING", null, null, 0, new BigDecimal("1.0001"), null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "PENDING", null, null, 0, null, COMPLETED_AT
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "PENDING", "PASS", null, 0, null, null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "PENDING", null, "TIMEOUT", 0, null, null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "PROCESSING", "PASS", null, 1, null, null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "PROCESSING", null, null, 1, null, COMPLETED_AT
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "SUCCEEDED", "PASS", null, 1, null, null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "SUCCEEDED", null, null, 1, null, COMPLETED_AT
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "SUCCEEDED", "PASS", "TIMEOUT", 1, null, COMPLETED_AT
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "FAILED", null, "TIMEOUT", 1, null, null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "FAILED", null, null, 1, null, COMPLETED_AT
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "FAILED", "REVIEW_REQUIRED", "TIMEOUT", 1, null, COMPLETED_AT
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        secondVerificationId, UUID.randomUUID(), "PROCESSING", null, "TIMEOUT", 1, null, null
                )),
                () -> assertThrows(DataAccessException.class, () -> insertAnalysis(
                        Long.MAX_VALUE, UUID.randomUUID(), "PENDING", null, null, 0, null, null
                ))
        );
    }

    @Test
    void succeededReviewAllowsAllObservationFieldsToRemainNull() {
        Long verificationId = submittedVerificationId();
        insertAnalysis(
                verificationId,
                UUID.randomUUID(),
                "SUCCEEDED",
                "REVIEW_REQUIRED",
                null,
                1,
                null,
                COMPLETED_AT
        );

        var row = jdbcTemplate.queryForMap(
                "select object_presence, relevance_score, anomaly_detected, framed_properly from verification_analysis where verification_id = ?",
                verificationId
        );
        assertAll(
                () -> assertNull(row.get("object_presence")),
                () -> assertNull(row.get("relevance_score")),
                () -> assertNull(row.get("anomaly_detected")),
                () -> assertNull(row.get("framed_properly"))
        );
    }

    @Test
    void failedRequiresCompletedAtAndFailureCodeWithoutRecommendation() {
        Long verificationId = submittedVerificationId();
        insertAnalysis(
                verificationId,
                UUID.randomUUID(),
                "FAILED",
                null,
                "TIMEOUT",
                1,
                null,
                COMPLETED_AT
        );

        var row = jdbcTemplate.queryForMap(
                "select status, recommendation, failure_code, completed_at from verification_analysis where verification_id = ?",
                verificationId
        );
        assertAll(
                () -> assertEquals("FAILED", row.get("status")),
                () -> assertNull(row.get("recommendation")),
                () -> assertEquals("TIMEOUT", row.get("failure_code")),
                () -> assertNotNull(row.get("completed_at"))
        );
    }

    @Test
    void legacyVerificationMayExistWithoutAnalysisAndParentDeleteDoesNotCascadeAudit() {
        Long legacyVerificationId = submittedVerificationId();
        assertFalse(repository.findByVerification_Id(legacyVerificationId).isPresent());

        Long auditedVerificationId = submittedVerificationId();
        insertAnalysis(auditedVerificationId, UUID.randomUUID(), "PENDING", null, null, 0, null, null);

        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update("delete from verification where id = ?", auditedVerificationId)
        );
        assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from verification_analysis where verification_id = ?",
                Integer.class,
                auditedVerificationId
        ));
    }

    @Test
    void flywayAppliedExactlyV1ThroughV6() {
        assertAll(
                () -> assertEquals(6, jdbcTemplate.queryForObject(
                        "select count(*) from flyway_schema_history where success = true and version is not null",
                        Integer.class
                )),
                () -> assertEquals(1, jdbcTemplate.queryForObject(
                        "select count(*) from flyway_schema_history where version = '6' and success = true",
                        Integer.class
                ))
        );
    }

    @Test
    void claimsOnlyOldestPendingAndCommitsBeforeReturning() {
        Long firstId = pendingAnalysisId();
        Long secondId = pendingAnalysisId();

        VerificationAnalysisClaim firstClaim = claimService.claimNextPending().orElseThrow();

        VerificationAnalysis first = repository.findById(firstId).orElseThrow();
        VerificationAnalysis second = repository.findById(secondId).orElseThrow();
        assertAll(
                () -> assertEquals(firstId, firstClaim.analysisId()),
                () -> assertEquals(1, firstClaim.attemptCount()),
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, first.getStatus()),
                () -> assertEquals(1, first.getAttemptCount()),
                () -> assertEquals(WORKER_NOW, first.getLastAttemptAt()),
                () -> assertEquals(VerificationAnalysisStatus.PENDING, second.getStatus()),
                () -> assertEquals(0, second.getAttemptCount()),
                () -> assertEquals(1, clock.reads()),
                () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        );

        assertEquals(secondId, claimService.claimNextPending().orElseThrow().analysisId());
        assertTrue(claimService.claimNextPending().isEmpty());
    }

    @Test
    void concurrentWorkersClaimOnePendingExactlyOnce() throws Exception {
        Long analysisId = pendingAnalysisId();

        List<Optional<VerificationAnalysisClaim>> results = concurrently(claimService::claimNextPending);

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(1, results.stream().filter(Optional::isPresent).count()),
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, analysis.getStatus()),
                () -> assertEquals(1, analysis.getAttemptCount()),
                () -> assertEquals(WORKER_NOW, analysis.getLastAttemptAt())
        );
    }

    @Test
    void nonStaleAndTerminalAnalysisAreNeverClaimed() {
        Long processingId = pendingAnalysisId();
        claimService.claimNextPending();

        Long succeededVerificationId = submittedVerificationId();
        insertAnalysis(
                succeededVerificationId,
                UUID.randomUUID(),
                "SUCCEEDED",
                "PASS",
                null,
                1,
                null,
                COMPLETED_AT
        );
        Long failedVerificationId = submittedVerificationId();
        insertAnalysis(
                failedVerificationId,
                UUID.randomUUID(),
                "FAILED",
                null,
                "TIMEOUT",
                1,
                null,
                COMPLETED_AT
        );

        assertTrue(claimService.claimNextPending().isEmpty());
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, repository.findById(processingId)
                        .orElseThrow().getStatus()),
                () -> assertEquals("SUCCEEDED", analysisStatus(succeededVerificationId)),
                () -> assertEquals("FAILED", analysisStatus(failedVerificationId))
        );
    }

    @Test
    void recoversOnlyAtInclusiveStaleBoundaryThenStartsNextAttempt() {
        Long analysisId = pendingAnalysisId();
        claimService.claimNextPending();

        clock.set(WORKER_NOW.plusSeconds(300).minus(1, java.time.temporal.ChronoUnit.MICROS));
        clock.resetReads();
        assertFalse(claimService.recoverNextStaleProcessing());
        assertEquals(1, clock.reads());

        clock.set(WORKER_NOW.plusSeconds(300));
        clock.resetReads();
        assertTrue(claimService.recoverNextStaleProcessing());
        VerificationAnalysis recovered = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PENDING, recovered.getStatus()),
                () -> assertEquals(1, recovered.getAttemptCount()),
                () -> assertEquals(WORKER_NOW, recovered.getLastAttemptAt()),
                () -> assertEquals(1, clock.reads())
        );

        VerificationAnalysisClaim retry = claimService.claimNextPending().orElseThrow();
        VerificationAnalysis processingAgain = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(analysisId, retry.analysisId()),
                () -> assertEquals(2, retry.attemptCount()),
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, processingAgain.getStatus()),
                () -> assertEquals(2, processingAgain.getAttemptCount()),
                () -> assertEquals(WORKER_NOW.plusSeconds(300), processingAgain.getLastAttemptAt())
        );
    }

    @Test
    void concurrentRecoveryRequeuesOneStaleAnalysisOnce() throws Exception {
        Long analysisId = pendingAnalysisId();
        claimService.claimNextPending();
        clock.set(WORKER_NOW.plusSeconds(300));

        List<Boolean> recovered = concurrently(claimService::recoverNextStaleProcessing);

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(1, recovered.stream().filter(Boolean::booleanValue).count()),
                () -> assertEquals(VerificationAnalysisStatus.PENDING, analysis.getStatus()),
                () -> assertEquals(1, analysis.getAttemptCount()),
                () -> assertEquals(WORKER_NOW, analysis.getLastAttemptAt())
        );
    }

    @Test
    void staleRecoveryIsOldestAttemptFirstAndLimitedToOne() {
        Long olderAttemptId = pendingAnalysisId();
        claimService.claimNextPending();

        clock.set(WORKER_NOW.plusSeconds(60));
        Long newerAttemptId = pendingAnalysisId();
        claimService.claimNextPending();

        clock.set(WORKER_NOW.plusSeconds(360));
        assertTrue(claimService.recoverNextStaleProcessing());

        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PENDING, repository.findById(olderAttemptId)
                        .orElseThrow().getStatus()),
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, repository.findById(newerAttemptId)
                        .orElseThrow().getStatus())
        );
    }

    @Test
    void claimRollbackRestoresPendingWithoutCountingAttempt() {
        Long analysisId = pendingAnalysisId();

        assertThrows(TestRollback.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            claimService.claimNextPending();
            throw new TestRollback();
        }));

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PENDING, analysis.getStatus()),
                () -> assertEquals(0, analysis.getAttemptCount()),
                () -> assertNull(analysis.getLastAttemptAt())
        );
    }

    @Test
    void persistsSuccessfulResultWithOneLockSelectAndOneUpdate() {
        VerificationAnalysisClaim claim = claimPendingAnalysis();
        clock.set(WORKER_NOW.plusSeconds(30).plusNanos(789));
        clock.resetReads();
        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        var statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        boolean applied;
        long statements;
        try {
            applied = resultService.completeSuccess(claim, successResult(AnalysisRecommendation.REVIEW_REQUIRED));
            statements = statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        VerificationAnalysis analysis = repository.findById(claim.analysisId()).orElseThrow();
        assertAll(
                () -> assertTrue(applied),
                () -> assertEquals(2, statements),
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
                () -> assertEquals(WORKER_NOW.plusSeconds(30), analysis.getCompletedAt()),
                () -> assertEquals(1, analysis.getAttemptCount()),
                () -> assertEquals(WORKER_NOW, analysis.getLastAttemptAt()),
                () -> assertEquals(1, clock.reads()),
                () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        );
    }

    @Test
    void persistsFailedResultWithoutChangingAttemptMetadata() {
        VerificationAnalysisClaim claim = claimPendingAnalysis();
        clock.set(WORKER_NOW.plusSeconds(30));
        clock.resetReads();

        assertTrue(resultService.completeFailure(claim, VerificationAnalysisFailureCode.TIMEOUT));

        VerificationAnalysis analysis = repository.findById(claim.analysisId()).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.FAILED, analysis.getStatus()),
                () -> assertEquals(VerificationAnalysisFailureCode.TIMEOUT, analysis.getFailureCode()),
                () -> assertNull(analysis.getRecommendation()),
                () -> assertEquals(WORKER_NOW.plusSeconds(30), analysis.getCompletedAt()),
                () -> assertEquals(1, analysis.getAttemptCount()),
                () -> assertEquals(WORKER_NOW, analysis.getLastAttemptAt()),
                () -> assertEquals(1, clock.reads()),
                () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        );
    }

    @Test
    void recoveryAndReclaimFenceLateSuccessThenAcceptCurrentSuccess() {
        VerificationAnalysisClaim oldClaim = claimPendingAnalysis();
        clock.set(WORKER_NOW.plusSeconds(300));
        assertTrue(claimService.recoverNextStaleProcessing());
        VerificationAnalysisClaim currentClaim = claimService.claimNextPending().orElseThrow();
        clock.set(WORKER_NOW.plusSeconds(330));
        clock.resetReads();

        assertFalse(resultService.completeSuccess(oldClaim, successResult(AnalysisRecommendation.PASS)));
        VerificationAnalysis stillProcessing = repository.findById(currentClaim.analysisId()).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, stillProcessing.getStatus()),
                () -> assertEquals(2, stillProcessing.getAttemptCount()),
                () -> assertNull(stillProcessing.getRecommendation()),
                () -> assertNull(stillProcessing.getCompletedAt()),
                () -> assertEquals(0, clock.reads())
        );

        assertTrue(resultService.completeSuccess(
                currentClaim,
                successResult(AnalysisRecommendation.REVIEW_REQUIRED)
        ));
        VerificationAnalysis succeeded = repository.findById(currentClaim.analysisId()).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.SUCCEEDED, succeeded.getStatus()),
                () -> assertEquals(2, succeeded.getAttemptCount()),
                () -> assertEquals(AnalysisRecommendation.REVIEW_REQUIRED, succeeded.getRecommendation()),
                () -> assertEquals(WORKER_NOW.plusSeconds(330), succeeded.getCompletedAt()),
                () -> assertEquals(1, clock.reads())
        );
    }

    @Test
    void staleFailureCannotDamageCurrentAttempt() {
        VerificationAnalysisClaim oldClaim = claimPendingAnalysis();
        clock.set(WORKER_NOW.plusSeconds(300));
        assertTrue(claimService.recoverNextStaleProcessing());
        VerificationAnalysisClaim currentClaim = claimService.claimNextPending().orElseThrow();
        clock.set(WORKER_NOW.plusSeconds(330));
        clock.resetReads();

        assertFalse(resultService.completeFailure(oldClaim, VerificationAnalysisFailureCode.TIMEOUT));

        VerificationAnalysis analysis = repository.findById(currentClaim.analysisId()).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, analysis.getStatus()),
                () -> assertEquals(2, analysis.getAttemptCount()),
                () -> assertNull(analysis.getFailureCode()),
                () -> assertNull(analysis.getCompletedAt()),
                () -> assertEquals(0, clock.reads())
        );
    }

    @Test
    void pendingTerminalDuplicateAndCrossAnalysisResultsAreRejected() {
        VerificationAnalysisClaim first = claimPendingAnalysis();
        clock.set(WORKER_NOW.plusSeconds(30));
        assertTrue(resultService.completeSuccess(first, successResult(AnalysisRecommendation.PASS)));
        clock.resetReads();
        assertAll(
                () -> assertFalse(resultService.completeSuccess(first, successResult(AnalysisRecommendation.PASS))),
                () -> assertFalse(resultService.completeFailure(first, VerificationAnalysisFailureCode.NETWORK)),
                () -> assertEquals(0, clock.reads())
        );

        VerificationAnalysisClaim failed = claimPendingAnalysis();
        assertTrue(resultService.completeFailure(failed, VerificationAnalysisFailureCode.TIMEOUT));
        clock.resetReads();
        assertAll(
                () -> assertFalse(resultService.completeSuccess(
                        failed,
                        successResult(AnalysisRecommendation.REVIEW_REQUIRED)
                )),
                () -> assertFalse(resultService.completeFailure(failed, VerificationAnalysisFailureCode.NETWORK)),
                () -> assertEquals(0, clock.reads())
        );

        Long pendingId = pendingAnalysisId();
        VerificationAnalysis pending = repository.findById(pendingId).orElseThrow();
        VerificationAnalysisClaim pendingToken = new VerificationAnalysisClaim(
                pendingId,
                pending.getAnalysisRequestId(),
                1
        );
        assertFalse(resultService.completeSuccess(
                pendingToken,
                successResult(AnalysisRecommendation.PASS)
        ));

        VerificationAnalysisClaim current = claimService.claimNextPending().orElseThrow();
        VerificationAnalysisClaim mixed = new VerificationAnalysisClaim(
                current.analysisId(),
                first.analysisRequestId(),
                current.attemptCount()
        );
        assertFalse(resultService.completeFailure(mixed, VerificationAnalysisFailureCode.TIMEOUT));
        assertEquals(
                VerificationAnalysisStatus.PROCESSING,
                repository.findById(current.analysisId()).orElseThrow().getStatus()
        );
    }

    @Test
    void successAndFailureCompletionRollBackWithoutPartialTerminalState() {
        VerificationAnalysisClaim success = claimPendingAnalysis();
        assertThrows(TestRollback.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertTrue(resultService.completeSuccess(success, successResult(AnalysisRecommendation.PASS)));
            throw new TestRollback();
        }));
        VerificationAnalysis successRolledBack = repository.findById(success.analysisId()).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, successRolledBack.getStatus()),
                () -> assertNull(successRolledBack.getRecommendation()),
                () -> assertNull(successRolledBack.getCompletedAt())
        );

        VerificationAnalysisClaim failure = claimPendingAnalysis();
        assertThrows(TestRollback.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertTrue(resultService.completeFailure(failure, VerificationAnalysisFailureCode.TIMEOUT));
            throw new TestRollback();
        }));
        VerificationAnalysis failureRolledBack = repository.findById(failure.analysisId()).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, failureRolledBack.getStatus()),
                () -> assertNull(failureRolledBack.getFailureCode()),
                () -> assertNull(failureRolledBack.getCompletedAt())
        );
    }

    @Test
    void concurrentSameAttemptCompletionCommitsExactlyOneTerminalResult() throws Exception {
        VerificationAnalysisClaim claim = claimPendingAnalysis();
        clock.set(WORKER_NOW.plusSeconds(30));

        List<Boolean> results = concurrently(
                () -> resultService.completeSuccess(claim, successResult(AnalysisRecommendation.PASS)),
                () -> resultService.completeFailure(claim, VerificationAnalysisFailureCode.TIMEOUT)
        );

        VerificationAnalysis analysis = repository.findById(claim.analysisId()).orElseThrow();
        assertAll(
                () -> assertEquals(1, results.stream().filter(Boolean::booleanValue).count()),
                () -> assertTrue(Set.of(
                        VerificationAnalysisStatus.SUCCEEDED,
                        VerificationAnalysisStatus.FAILED
                ).contains(analysis.getStatus())),
                () -> assertEquals(
                        analysis.getStatus() == VerificationAnalysisStatus.SUCCEEDED,
                        analysis.getRecommendation() != null
                ),
                () -> assertEquals(
                        analysis.getStatus() == VerificationAnalysisStatus.FAILED,
                        analysis.getFailureCode() != null
                ),
                () -> assertNotNull(analysis.getCompletedAt())
        );
    }

    @Test
    void concurrentOldAndCurrentAttemptCompletionAcceptsOnlyCurrentResult() throws Exception {
        VerificationAnalysisClaim oldClaim = claimPendingAnalysis();
        clock.set(WORKER_NOW.plusSeconds(300));
        assertTrue(claimService.recoverNextStaleProcessing());
        VerificationAnalysisClaim currentClaim = claimService.claimNextPending().orElseThrow();
        clock.set(WORKER_NOW.plusSeconds(330));

        List<Boolean> results = concurrently(
                () -> resultService.completeSuccess(oldClaim, successResult(AnalysisRecommendation.PASS)),
                () -> resultService.completeSuccess(
                        currentClaim,
                        successResult(AnalysisRecommendation.REVIEW_REQUIRED)
                )
        );

        VerificationAnalysis analysis = repository.findById(currentClaim.analysisId()).orElseThrow();
        assertAll(
                () -> assertEquals(List.of(false, true), results),
                () -> assertEquals(VerificationAnalysisStatus.SUCCEEDED, analysis.getStatus()),
                () -> assertEquals(2, analysis.getAttemptCount()),
                () -> assertEquals(AnalysisRecommendation.REVIEW_REQUIRED, analysis.getRecommendation()),
                () -> assertEquals(WORKER_NOW.plusSeconds(330), analysis.getCompletedAt())
        );
    }

    private Long submittedVerificationId() {
        return inTransaction(() -> persistSubmittedVerification().getId());
    }

    private Long pendingAnalysisId() {
        return inTransaction(() -> {
            Verification verification = persistSubmittedVerification();
            VerificationAnalysis analysis = VerificationAnalysis.createPending(verification, UUID.randomUUID());
            repository.saveAndFlush(analysis);
            return analysis.getId();
        });
    }

    private VerificationAnalysisClaim claimPendingAnalysis() {
        Long analysisId = pendingAnalysisId();
        return claimService.claimNextPending()
                .filter(claim -> claim.analysisId().equals(analysisId))
                .orElseThrow();
    }

    private VerificationAnalysisSuccessResult successResult(AnalysisRecommendation recommendation) {
        return new VerificationAnalysisSuccessResult(
                recommendation,
                "synthetic-reason",
                "synthetic-model",
                "synthetic-criteria",
                true,
                new BigDecimal("0.7500"),
                false,
                true
        );
    }

    private String analysisStatus(Long verificationId) {
        return jdbcTemplate.queryForObject(
                "select status from verification_analysis where verification_id = ?",
                String.class,
                verificationId
        );
    }

    private Verification persistSubmittedVerification() {
        User user = User.create();
        RoutineDefinition definition = new RoutineDefinition("water", null);
        entityManager.persist(user);
        entityManager.persist(definition);
        RoutineGroup group = new RoutineGroup(
                definition,
                user,
                "water group",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.ACTIVE,
                5,
                1
        );
        entityManager.persist(group);
        RoutineSchedule schedule = new RoutineSchedule(
                group,
                ScheduleType.DAILY,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 16),
                LocalTime.of(23, 0),
                "Asia/Seoul",
                Set.of()
        );
        entityManager.persist(schedule);
        GroupMember member = new GroupMember(
                group,
                user,
                GroupMemberRole.OWNER,
                GroupMemberStatus.JOINED,
                Instant.parse("2026-08-01T00:00:00Z")
        );
        member.startParticipation(Instant.parse("2026-08-01T00:00:00Z"));
        entityManager.persist(member);
        Verification verification = Verification.create(member, schedule, LocalDate.of(2026, 8, 11));
        verification.submit(Clock.fixed(SUBMITTED_AT, ZoneOffset.UTC));
        entityManager.persist(verification);
        entityManager.flush();
        return verification;
    }

    private void insertAnalysis(
            Long verificationId,
            UUID requestId,
            String status,
            String recommendation,
            String failureCode,
            int attemptCount,
            BigDecimal relevanceScore,
            Instant completedAt
    ) {
        jdbcTemplate.update(
                """
                        insert into verification_analysis (
                            verification_id,
                            analysis_request_id,
                            status,
                            recommendation,
                            failure_code,
                            attempt_count,
                            relevance_score,
                            completed_at,
                            created_at,
                            updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                        """,
                verificationId,
                requestId.toString(),
                status,
                recommendation,
                failureCode,
                attemptCount,
                relevanceScore,
                completedAt == null ? null : Timestamp.from(completedAt)
        );
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private <T> List<T> concurrently(Supplier<T> command) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> first = executor.submit(() -> runWhenReleased(command, ready, start));
            Future<T> second = executor.submit(() -> runWhenReleased(command, ready, start));
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent workers did not become ready");
            }
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> List<T> concurrently(Supplier<T> firstCommand, Supplier<T> secondCommand) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> first = executor.submit(() -> runWhenReleased(firstCommand, ready, start));
            Future<T> second = executor.submit(() -> runWhenReleased(secondCommand, ready, start));
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent workers did not become ready");
            }
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> T runWhenReleased(
            Supplier<T> command,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent worker was not released");
        }
        return command.get();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WorkerTestConfiguration {

        @Bean
        @Primary
        MutableClock verificationAnalysisWorkerClock() {
            return new MutableClock(WORKER_NOW);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final AtomicInteger reads = new AtomicInteger();

        MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void set(Instant value) {
            instant.set(value);
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
            return instant.get();
        }
    }

    private static final class TestRollback extends RuntimeException {
    }
}

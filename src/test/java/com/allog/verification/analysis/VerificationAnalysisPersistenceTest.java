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
import com.allog.verification.analysis.service.VerificationAnalysisInput;
import com.allog.verification.analysis.service.VerificationAnalysisInputLoader;
import com.allog.verification.analysis.service.VerificationAnalysisMediaProcessor;
import com.allog.verification.analysis.service.VerificationAnalysisProcessor;
import com.allog.verification.analysis.service.VerificationAnalysisProvider;
import com.allog.verification.analysis.service.VerificationAnalysisResultService;
import com.allog.verification.analysis.service.VerificationAnalysisSuccessResult;
import com.allog.verification.analysis.service.VerificationAnalysisWorker;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationMedia;
import com.allog.verification.storage.VerificationMediaProperties;
import com.allog.verification.storage.VerificationMediaStorage;
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
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private VerificationAnalysisInputLoader inputLoader;

    @Autowired
    private VerificationAnalysisWorker productionWorker;

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

    @Test
    void productionWorkerWithoutProcessorDoesNotClaim() {
        Long analysisId = pendingAnalysisId();

        assertEquals(
                VerificationAnalysisWorker.ExecutionResult.PROCESSOR_UNAVAILABLE,
                productionWorker.processNext()
        );

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PENDING, analysis.getStatus()),
                () -> assertEquals(0, analysis.getAttemptCount()),
                () -> assertNull(analysis.getLastAttemptAt()),
                () -> assertEquals(0, clock.reads())
        );
    }

    @Test
    void workerPersistsSyntheticSuccessWithFiveStatementsAndTransactionSeparation() {
        Long analysisId = pendingAnalysisId();
        AtomicBoolean processorTransaction = new AtomicBoolean(true);
        VerificationAnalysisWorker worker = worker(claim -> {
            processorTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new VerificationAnalysisProcessor.Success(successResult(AnalysisRecommendation.PASS));
        });
        clock.resetReads();
        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        var statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        VerificationAnalysisWorker.ExecutionResult executionResult;
        long statements;
        try {
            executionResult = worker.processNext();
            statements = statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisWorker.ExecutionResult.COMPLETED, executionResult),
                () -> assertEquals(5, statements),
                () -> assertFalse(processorTransaction.get()),
                () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive()),
                () -> assertEquals(2, clock.reads()),
                () -> assertEquals(2, clock.transactionalReads()),
                () -> assertEquals(VerificationAnalysisStatus.SUCCEEDED, analysis.getStatus()),
                () -> assertEquals(AnalysisRecommendation.PASS, analysis.getRecommendation()),
                () -> assertEquals(WORKER_NOW, analysis.getCompletedAt()),
                () -> assertEquals(1, analysis.getAttemptCount()),
                () -> assertEquals(WORKER_NOW, analysis.getLastAttemptAt())
        );
    }

    @Test
    void workerPersistsProcessorClassifiedFailureWithoutRetry() {
        Long analysisId = pendingAnalysisId();
        AtomicInteger calls = new AtomicInteger();
        VerificationAnalysisWorker worker = worker(claim -> {
            calls.incrementAndGet();
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new VerificationAnalysisProcessor.Failure(VerificationAnalysisFailureCode.TIMEOUT);
        });

        assertEquals(VerificationAnalysisWorker.ExecutionResult.COMPLETED, worker.processNext());

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(1, calls.get()),
                () -> assertEquals(VerificationAnalysisStatus.FAILED, analysis.getStatus()),
                () -> assertEquals(VerificationAnalysisFailureCode.TIMEOUT, analysis.getFailureCode()),
                () -> assertNull(analysis.getRecommendation()),
                () -> assertEquals(WORKER_NOW, analysis.getCompletedAt()),
                () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        );
    }

    @Test
    void workerWithProcessorAndNoPendingAnalysisIsNoWork() {
        AtomicInteger calls = new AtomicInteger();
        VerificationAnalysisWorker worker = worker(claim -> {
            calls.incrementAndGet();
            return new VerificationAnalysisProcessor.Success(successResult(AnalysisRecommendation.PASS));
        });

        assertEquals(VerificationAnalysisWorker.ExecutionResult.NO_WORK, worker.processNext());
        assertEquals(0, calls.get());
    }

    @Test
    void staleWorkerResultIsRejectedAfterCurrentWorkerCompletes() throws Exception {
        Long analysisId = pendingAnalysisId();
        CountDownLatch processorStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessor = new CountDownLatch(1);
        AtomicBoolean oldProcessorTransaction = new AtomicBoolean(true);
        VerificationAnalysisWorker oldWorker = worker(claim -> {
            oldProcessorTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            processorStarted.countDown();
            await(releaseProcessor);
            return new VerificationAnalysisProcessor.Success(successResult(AnalysisRecommendation.PASS));
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<VerificationAnalysisWorker.ExecutionResult> oldResult = executor.submit(oldWorker::processNext);
            assertTrue(processorStarted.await(5, TimeUnit.SECONDS));

            clock.set(WORKER_NOW.plusSeconds(300));
            assertTrue(claimService.recoverNextStaleProcessing());
            clock.set(WORKER_NOW.plusSeconds(330));
            VerificationAnalysisWorker currentWorker = worker(claim ->
                    new VerificationAnalysisProcessor.Success(successResult(AnalysisRecommendation.REVIEW_REQUIRED))
            );
            assertEquals(VerificationAnalysisWorker.ExecutionResult.COMPLETED, currentWorker.processNext());

            releaseProcessor.countDown();
            assertEquals(
                    VerificationAnalysisWorker.ExecutionResult.STALE_RESULT_REJECTED,
                    oldResult.get(10, TimeUnit.SECONDS)
            );
        } finally {
            releaseProcessor.countDown();
            executor.shutdownNow();
        }

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertFalse(oldProcessorTransaction.get()),
                () -> assertEquals(VerificationAnalysisStatus.SUCCEEDED, analysis.getStatus()),
                () -> assertEquals(2, analysis.getAttemptCount()),
                () -> assertEquals(AnalysisRecommendation.REVIEW_REQUIRED, analysis.getRecommendation()),
                () -> assertEquals(WORKER_NOW.plusSeconds(330), analysis.getCompletedAt())
        );
    }

    @Test
    void concurrentWorkersProcessOnePendingAnalysisOnce() throws Exception {
        Long analysisId = pendingAnalysisId();
        AtomicInteger processorCalls = new AtomicInteger();
        VerificationAnalysisProcessor processor = claim -> {
            processorCalls.incrementAndGet();
            return new VerificationAnalysisProcessor.Success(successResult(AnalysisRecommendation.PASS));
        };
        VerificationAnalysisWorker firstWorker = worker(processor);
        VerificationAnalysisWorker secondWorker = worker(processor);

        List<VerificationAnalysisWorker.ExecutionResult> results = concurrently(
                firstWorker::processNext,
                secondWorker::processNext
        );

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(1, processorCalls.get()),
                () -> assertEquals(1, results.stream()
                        .filter(result -> result == VerificationAnalysisWorker.ExecutionResult.COMPLETED)
                        .count()),
                () -> assertEquals(1, results.stream()
                        .filter(result -> result == VerificationAnalysisWorker.ExecutionResult.NO_WORK)
                        .count()),
                () -> assertEquals(VerificationAnalysisStatus.SUCCEEDED, analysis.getStatus()),
                () -> assertEquals(1, analysis.getAttemptCount())
        );
    }

    @Test
    void processorRuntimeExceptionLeavesRecoverableProcessingAttempt() {
        Long analysisId = pendingAnalysisId();
        VerificationAnalysisWorker worker = worker(claim -> {
            throw new IllegalStateException("synthetic processor failure");
        });

        assertEquals(VerificationAnalysisWorker.ExecutionResult.PROCESSOR_EXCEPTION, worker.processNext());
        VerificationAnalysis processing = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, processing.getStatus()),
                () -> assertNull(processing.getRecommendation()),
                () -> assertNull(processing.getFailureCode()),
                () -> assertNull(processing.getCompletedAt())
        );

        clock.set(WORKER_NOW.plusSeconds(300));
        assertTrue(claimService.recoverNextStaleProcessing());
        assertEquals(
                VerificationAnalysisStatus.PENDING,
                repository.findById(analysisId).orElseThrow().getStatus()
        );
    }

    @Test
    void resultFailureRollsBackAndPropagatesWithoutPartialTerminalState() {
        Long analysisId = pendingAnalysisId();
        VerificationAnalysisWorker worker = worker(claim -> {
            clock.set(WORKER_NOW.minusSeconds(1));
            return new VerificationAnalysisProcessor.Success(successResult(AnalysisRecommendation.PASS));
        });

        assertThrows(IllegalStateException.class, worker::processNext);

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisStatus.PROCESSING, analysis.getStatus()),
                () -> assertEquals(1, analysis.getAttemptCount()),
                () -> assertEquals(WORKER_NOW, analysis.getLastAttemptAt()),
                () -> assertNull(analysis.getRecommendation()),
                () -> assertNull(analysis.getFailureCode()),
                () -> assertNull(analysis.getCompletedAt()),
                () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        );
    }

    @Test
    void loadsCurrentAttemptConfirmedMediaInputWithTwoQueries() {
        Long analysisId = pendingAnalysisWithMedia("video/mp4", 4, true);
        VerificationAnalysisClaim claim = claimService.claimNextPending().orElseThrow();
        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        var statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        VerificationAnalysisInput input;
        long statements;
        try {
            input = inputLoader.load(claim);
            statements = statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        assertAll(
                () -> assertEquals(analysisId, input.analysisId()),
                () -> assertEquals(claim.analysisRequestId(), input.analysisRequestId()),
                () -> assertEquals(claim.attemptCount(), input.attemptCount()),
                () -> assertEquals("video/mp4", input.contentType()),
                () -> assertEquals(4, input.sizeBytes()),
                () -> assertTrue(input.objectKey().startsWith("verification-media/")),
                () -> assertEquals(2, statements),
                () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        );
    }

    @Test
    void inputLoaderRejectsStaleClaimAndInvalidVerificationState() {
        Long staleId = pendingAnalysisWithMedia("video/mp4", 4, true);
        VerificationAnalysisClaim current = claimService.claimNextPending().orElseThrow();
        VerificationAnalysisClaim stale = new VerificationAnalysisClaim(
                staleId,
                current.analysisRequestId(),
                current.attemptCount() + 1
        );
        assertLoadReason(VerificationAnalysisInputLoader.Reason.STALE_CLAIM, stale);

        Long invalidVerificationId = pendingAnalysisWithMedia("video/mp4", 4, true);
        VerificationAnalysisClaim invalidVerification = claimService.claimNextPending().orElseThrow();
        jdbcTemplate.update(
                "update verification set status = 'PROCESSING' where id = "
                        + "(select verification_id from verification_analysis where id = ?)",
                invalidVerificationId
        );
        assertLoadReason(
                VerificationAnalysisInputLoader.Reason.INVALID_VERIFICATION,
                invalidVerification
        );
    }

    @Test
    void inputLoaderRejectsMissingAndUnconfirmedMedia() {
        Long missingMediaId = pendingAnalysisId();
        VerificationAnalysisClaim missingMedia = claimService.claimNextPending().orElseThrow();
        assertEquals(missingMediaId, missingMedia.analysisId());
        assertLoadReason(VerificationAnalysisInputLoader.Reason.MISSING_MEDIA, missingMedia);

        Long unconfirmedId = pendingAnalysisWithMedia("video/mp4", 4, false);
        VerificationAnalysisClaim unconfirmed = claimService.claimNextPending().orElseThrow();
        assertEquals(unconfirmedId, unconfirmed.analysisId());
        assertLoadReason(VerificationAnalysisInputLoader.Reason.UNCONFIRMED_MEDIA, unconfirmed);
    }

    @Test
    void inputLoaderRejectsBlankKeyUnsupportedTypeAndOversize() {
        Long blankKeyId = pendingAnalysisWithMedia("video/mp4", 4, true);
        VerificationAnalysisClaim blankKey = claimService.claimNextPending().orElseThrow();
        jdbcTemplate.update(
                "update verification_media set object_key = '' where verification_id = "
                        + "(select verification_id from verification_analysis where id = ?)",
                blankKeyId
        );
        assertLoadReason(VerificationAnalysisInputLoader.Reason.INVALID_MEDIA, blankKey);

        pendingAnalysisWithMedia("application/octet-stream", 4, true);
        VerificationAnalysisClaim unsupported = claimService.claimNextPending().orElseThrow();
        assertLoadReason(VerificationAnalysisInputLoader.Reason.INVALID_MEDIA, unsupported);

        pendingAnalysisWithMedia("video/mp4", 1_025, true);
        VerificationAnalysisClaim oversized = claimService.claimNextPending().orElseThrow();
        assertLoadReason(VerificationAnalysisInputLoader.Reason.INVALID_MEDIA, oversized);
    }

    @Test
    void mediaProcessorCompletesWorkerWithSevenStatementsAndNoExternalTransaction() {
        Long analysisId = pendingAnalysisWithMedia("video/mp4", 4, true);
        String objectKey = analysisObjectKey(analysisId);
        TrackingAcquisitionStorage storage = new TrackingAcquisitionStorage(new VerificationMediaStorage.StoredMedia(
                objectKey,
                4,
                "video/mp4",
                new byte[]{1, 2, 3, 4}
        ));
        AtomicBoolean providerTransaction = new AtomicBoolean(true);
        VerificationAnalysisProvider provider = (input, media) -> {
            providerTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new VerificationAnalysisProcessor.Success(successResult(AnalysisRecommendation.PASS));
        };
        VerificationAnalysisWorker worker = worker(new VerificationAnalysisMediaProcessor(
                inputLoader,
                storage,
                provider
        ));
        clock.resetReads();
        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        var statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        VerificationAnalysisWorker.ExecutionResult result;
        long statements;
        try {
            result = worker.processNext();
            statements = statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationAnalysisWorker.ExecutionResult.COMPLETED, result),
                () -> assertEquals(7, statements),
                () -> assertFalse(storage.transactionActive()),
                () -> assertFalse(providerTransaction.get()),
                () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive()),
                () -> assertEquals(2, clock.reads()),
                () -> assertEquals(2, clock.transactionalReads()),
                () -> assertEquals(VerificationAnalysisStatus.SUCCEEDED, analysis.getStatus()),
                () -> assertEquals(AnalysisRecommendation.PASS, analysis.getRecommendation())
        );
    }

    @Test
    void staleMediaProcessorResultCannotOverwriteCurrentAttempt() throws Exception {
        Long analysisId = pendingAnalysisWithMedia("video/mp4", 4, true);
        String objectKey = analysisObjectKey(analysisId);
        TrackingAcquisitionStorage storage = new TrackingAcquisitionStorage(new VerificationMediaStorage.StoredMedia(
                objectKey,
                4,
                "video/mp4",
                new byte[]{1, 2, 3, 4}
        ));
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        VerificationAnalysisProvider blockingProvider = (input, media) -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            providerStarted.countDown();
            await(releaseProvider);
            return new VerificationAnalysisProcessor.Success(successResult(AnalysisRecommendation.PASS));
        };
        VerificationAnalysisWorker oldWorker = worker(new VerificationAnalysisMediaProcessor(
                inputLoader,
                storage,
                blockingProvider
        ));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<VerificationAnalysisWorker.ExecutionResult> oldResult = executor.submit(oldWorker::processNext);
            assertTrue(providerStarted.await(5, TimeUnit.SECONDS));

            clock.set(WORKER_NOW.plusSeconds(300));
            assertTrue(claimService.recoverNextStaleProcessing());
            clock.set(WORKER_NOW.plusSeconds(330));
            VerificationAnalysisWorker currentWorker = worker(claim ->
                    new VerificationAnalysisProcessor.Success(successResult(AnalysisRecommendation.REVIEW_REQUIRED))
            );
            assertEquals(VerificationAnalysisWorker.ExecutionResult.COMPLETED, currentWorker.processNext());

            releaseProvider.countDown();
            assertEquals(
                    VerificationAnalysisWorker.ExecutionResult.STALE_RESULT_REJECTED,
                    oldResult.get(10, TimeUnit.SECONDS)
            );
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }

        VerificationAnalysis analysis = repository.findById(analysisId).orElseThrow();
        assertAll(
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

    private Long pendingAnalysisWithMedia(String contentType, long sizeBytes, boolean confirmed) {
        return inTransaction(() -> {
            Verification verification = persistSubmittedVerification();
            VerificationMedia media = VerificationMedia.create(
                    verification,
                    "verification-media/" + UUID.randomUUID(),
                    contentType,
                    sizeBytes
            );
            if (confirmed) {
                media.confirm(sizeBytes, Clock.fixed(WORKER_NOW, ZoneOffset.UTC));
            }
            entityManager.persist(media);
            VerificationAnalysis analysis = VerificationAnalysis.createPending(
                    verification,
                    UUID.randomUUID()
            );
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

    private VerificationAnalysisWorker worker(VerificationAnalysisProcessor processor) {
        return new VerificationAnalysisWorker(claimService, resultService, Optional.of(processor));
    }

    private void assertLoadReason(
            VerificationAnalysisInputLoader.Reason expected,
            VerificationAnalysisClaim claim
    ) {
        VerificationAnalysisInputLoader.LoadException exception = assertThrows(
                VerificationAnalysisInputLoader.LoadException.class,
                () -> inputLoader.load(claim)
        );
        assertEquals(expected, exception.reason());
    }

    private String analysisObjectKey(Long analysisId) {
        return jdbcTemplate.queryForObject(
                """
                        select media.object_key
                        from verification_analysis analysis
                        join verification_media media on media.verification_id = analysis.verification_id
                        where analysis.id = ?
                        """,
                String.class,
                analysisId
        );
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

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("processor was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("processor was interrupted", exception);
        }
    }

    static final class TrackingAcquisitionStorage implements VerificationMediaStorage {

        private final StoredMedia media;
        private volatile boolean transactionActive;

        TrackingAcquisitionStorage(StoredMedia media) {
            this.media = media;
        }

        @Override
        public UploadGrant issueUpload(String objectKey, String contentType, long sizeBytes, Instant expiresAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredMediaInspection inspect(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredMedia acquire(String objectKey, long maxBytes) {
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            assertEquals(media.objectKey(), objectKey);
            assertEquals(media.contentLength(), maxBytes);
            return media;
        }

        boolean transactionActive() {
            return transactionActive;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WorkerTestConfiguration {

        @Bean
        @Primary
        MutableClock verificationAnalysisWorkerClock() {
            return new MutableClock(WORKER_NOW);
        }

        @Bean
        @Primary
        VerificationMediaProperties verificationAnalysisMediaProperties() {
            return new VerificationMediaProperties(
                    true,
                    "test-bucket",
                    "ap-northeast-2",
                    1_024,
                    Duration.ofMinutes(5),
                    Set.of("video/mp4", "image/jpeg")
            );
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger transactionalReads = new AtomicInteger();

        MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void set(Instant value) {
            instant.set(value);
        }

        int reads() {
            return reads.get();
        }

        int transactionalReads() {
            return transactionalReads.get();
        }

        void resetReads() {
            reads.set(0);
            transactionalReads.set(0);
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
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                transactionalReads.incrementAndGet();
            }
            return instant.get();
        }
    }

    private static final class TestRollback extends RuntimeException {
    }
}

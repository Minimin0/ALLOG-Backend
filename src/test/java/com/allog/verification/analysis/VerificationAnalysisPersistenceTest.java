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
import com.allog.verification.analysis.domain.VerificationAnalysis;
import com.allog.verification.analysis.domain.VerificationAnalysisStatus;
import com.allog.verification.analysis.repository.VerificationAnalysisRepository;
import com.allog.verification.domain.Verification;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class VerificationAnalysisPersistenceTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-11T10:01:00Z");

    @Autowired
    private VerificationAnalysisRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    private Long submittedVerificationId() {
        return inTransaction(() -> persistSubmittedVerification().getId());
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
}

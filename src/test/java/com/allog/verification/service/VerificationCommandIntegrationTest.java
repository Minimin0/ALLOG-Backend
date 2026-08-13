package com.allog.verification.service;

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
import com.allog.verification.domain.Verification;
import com.allog.verification.repository.VerificationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "spring.datasource.url=${VERIFICATION_TEST_DB_URL:jdbc:h2:mem:verification-command;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${VERIFICATION_TEST_DB_USERNAME:sa}",
        "spring.datasource.password=${VERIFICATION_TEST_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=${VERIFICATION_TEST_DB_DRIVER:org.h2.Driver}"
})
@ActiveProfiles("test")
@Import(VerificationCommandIntegrationTest.TestConfig.class)
class VerificationCommandIntegrationTest {

    private static final Instant SNAPSHOT_NOW = Instant.parse("2026-08-11T10:00:00Z");

    @Autowired
    private VerificationCommandService service;

    @Autowired
    private VerificationRepository verificationRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createOrGetPersistsOneUnchangedCurrentSlot() {
        Fixture fixture = fixture();

        Verification first = service.createOrGetCurrent(fixture.groupId(), fixture.userId());
        Verification second = service.createOrGetCurrent(fixture.groupId(), fixture.userId());

        assertAll(
                () -> assertEquals(first.getId(), second.getId()),
                () -> assertEquals(1, currentRows(fixture)),
                () -> assertEquals(first.getStatus(), second.getStatus()),
                () -> assertEquals(first.getSubmittedAt(), second.getSubmittedAt())
        );
    }

    @Test
    void concurrentCreateIsSerializedWithoutLeakingUniqueViolation() throws Exception {
        Fixture fixture = fixture();

        List<Long> ids = concurrently(() -> service.createOrGetCurrent(
                fixture.groupId(), fixture.userId()
        ).getId());

        assertAll(
                () -> assertEquals(ids.get(0), ids.get(1)),
                () -> assertEquals(1, currentRows(fixture))
        );
    }

    @Test
    void concurrentSubmitIsIdempotentAndPreservesFirstTimestamp() throws Exception {
        Fixture fixture = fixture();
        service.createOrGetCurrent(fixture.groupId(), fixture.userId());

        List<Instant> submittedAt = concurrently(() -> service.submitCurrent(
                fixture.groupId(), fixture.userId()
        ).getSubmittedAt());

        assertAll(
                () -> assertEquals(SNAPSHOT_NOW, submittedAt.get(0)),
                () -> assertEquals(submittedAt.get(0), submittedAt.get(1)),
                () -> assertEquals(SNAPSHOT_NOW, inTransaction(() -> verificationRepository
                        .findByGroupMember_IdAndRoutineSchedule_IdAndScheduledDate(
                                fixture.memberId(), fixture.scheduleId(), LocalDate.of(2026, 8, 11)
                        )
                        .orElseThrow()
                        .getSubmittedAt()))
        );
    }

    @Test
    void flywayHasExactlyV1ThroughV4() {
        assertAll(
                () -> assertEquals(4, jdbcTemplate.queryForObject(
                        "select count(*) from flyway_schema_history where success = true and version is not null",
                        Integer.class
                )),
                () -> assertEquals(0, jdbcTemplate.queryForObject(
                        "select count(*) from flyway_schema_history where version = '5'",
                        Integer.class
                ))
        );
    }

    private Fixture fixture() {
        return inTransaction(() -> {
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
            entityManager.flush();
            return new Fixture(group.getId(), user.getId(), member.getId(), schedule.getId());
        });
    }

    private int currentRows(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from verification
                        where group_member_id = ?
                          and routine_schedule_id = ?
                          and scheduled_date = ?
                        """,
                Integer.class,
                fixture.memberId(),
                fixture.scheduleId(),
                LocalDate.of(2026, 8, 11)
        );
    }

    private <T> List<T> concurrently(Supplier<T> command) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> first = executor.submit(() -> runWhenReleased(command, ready, start));
            Future<T> second = executor.submit(() -> runWhenReleased(command, ready, start));
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent commands did not become ready");
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
            throw new IllegalStateException("concurrent command was not released");
        }
        return command.get();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private record Fixture(Long groupId, Long userId, Long memberId, Long scheduleId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        Clock verificationCommandTestClock() {
            return Clock.fixed(SNAPSHOT_NOW, ZoneOffset.UTC);
        }
    }
}

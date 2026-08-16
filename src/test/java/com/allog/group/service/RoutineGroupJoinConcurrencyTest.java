package com.allog.group.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Capacity is only safe if the database says so, so this runs against a real MySQL and is skipped
 * otherwise: H2 locking would prove nothing about InnoDB. Enable by pointing
 * GROUP_JOIN_CONCURRENCY_DB_URL at a MySQL 8 instance.
 */
@EnabledIfEnvironmentVariable(named = "GROUP_JOIN_CONCURRENCY_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${GROUP_JOIN_CONCURRENCY_DB_URL}",
        "spring.datasource.username=${GROUP_JOIN_CONCURRENCY_DB_USERNAME}",
        "spring.datasource.password=${GROUP_JOIN_CONCURRENCY_DB_PASSWORD}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@ActiveProfiles("test")
class RoutineGroupJoinConcurrencyTest {

    private static final int MAX_MEMBERS = 2;

    @Autowired
    private RoutineGroupJoinService joinService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        inTransaction(() -> {
            // Schedules first: routine_schedule holds an FK to routine_group.
            entityManager.createQuery("select s from RoutineSchedule s", RoutineSchedule.class)
                    .getResultList().forEach(entityManager::remove);
            entityManager.createQuery("select m from GroupMember m", GroupMember.class)
                    .getResultList().forEach(entityManager::remove);
            entityManager.createQuery("select g from RoutineGroup g", RoutineGroup.class)
                    .getResultList().forEach(entityManager::remove);
            entityManager.flush();
            return null;
        });
    }

    @RepeatedTest(8)
    void twoUsersRacingForTheLastSlotProduceExactlyOneJoin() throws Exception {
        Fixture fixture = fixture();

        List<Outcome> outcomes = raceJoins(fixture.groupId(), fixture.firstJoinerId(), fixture.secondJoinerId());

        long succeeded = outcomes.stream().filter(Outcome::succeeded).count();
        assertEquals(1, succeeded, "exactly one of the two racing joins may succeed");
        outcomes.stream().filter(outcome -> !outcome.succeeded()).forEach(outcome ->
                assertInstanceOf(RoutineGroupJoinException.class, outcome.failure(),
                        "the losing join must fail as a domain conflict, not a raw database error"));

        inTransaction(() -> {
            // The owner holds one slot, so a correct run lands on exactly MAX_MEMBERS and never above.
            // Filling the room also starts it, so those members are now ACTIVE rather than JOINED.
            assertEquals(MAX_MEMBERS, activeCount(fixture.groupId()), "capacity was oversubscribed");
            assertEquals(0, joinedCount(fixture.groupId()));
            assertEquals(RoutineGroupStatus.ACTIVE, entityManager
                    .find(RoutineGroup.class, fixture.groupId()).getStatus());
            return null;
        });
    }

    @RepeatedTest(8)
    void sameUserRacingItselfProducesExactlyOneMembership() throws Exception {
        Fixture fixture = fixture();

        List<Outcome> outcomes = raceJoins(fixture.groupId(), fixture.firstJoinerId(), fixture.firstJoinerId());

        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count(),
                "a user must not join the same group twice");
        inTransaction(() -> {
            assertEquals(1, entityManager.createQuery(
                            "select count(m) from GroupMember m where m.routineGroup.id = :g and m.user.id = :u",
                            Long.class)
                    .setParameter("g", fixture.groupId())
                    .setParameter("u", fixture.firstJoinerId())
                    .getSingleResult());
            return null;
        });
    }

    private List<Outcome> raceJoins(Long groupId, Long firstUserId, Long secondUserId) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> first = attempt(groupId, firstUserId, startTogether);
            Callable<Outcome> second = attempt(groupId, secondUserId, startTogether);
            Future<Outcome> firstResult = executor.submit(first);
            Future<Outcome> secondResult = executor.submit(second);
            return List.of(firstResult.get(), secondResult.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Outcome> attempt(Long groupId, Long userId, CyclicBarrier startTogether) {
        return () -> {
            startTogether.await();
            try {
                joinService.join(groupId, userId);
                return new Outcome(true, null);
            } catch (RuntimeException exception) {
                return new Outcome(false, exception);
            }
        };
    }

    private Fixture fixture() {
        return inTransaction(() -> {
            User owner = User.create();
            User first = User.create();
            User second = User.create();
            entityManager.persist(owner);
            entityManager.persist(first);
            entityManager.persist(second);
            RoutineDefinition definition = new RoutineDefinition("아침 식사", "설명");
            entityManager.persist(definition);
            RoutineGroup group = new RoutineGroup(
                    definition,
                    owner,
                    "경쟁 그룹",
                    GroupVisibility.PUBLIC,
                    RoutineGroupStatus.RECRUITING,
                    MAX_MEMBERS,
                    1
            );
            entityManager.persist(group);
            entityManager.persist(new RoutineSchedule(
                    group,
                    ScheduleType.DAILY,
                    LocalDate.now().minusDays(1),
                    LocalDate.now().plusYears(1),
                    LocalTime.of(23, 0),
                    "Asia/Seoul",
                    Set.of()
            ));
            entityManager.persist(new GroupMember(
                    group,
                    owner,
                    GroupMemberRole.OWNER,
                    GroupMemberStatus.JOINED,
                    Instant.parse("2026-09-01T00:00:00Z")
            ));
            entityManager.flush();
            return new Fixture(group.getId(), first.getId(), second.getId());
        });
    }

    private long activeCount(Long groupId) {
        return entityManager.createQuery(
                        "select count(m) from GroupMember m where m.routineGroup.id = :g and m.status = :s",
                        Long.class)
                .setParameter("g", groupId)
                .setParameter("s", GroupMemberStatus.ACTIVE)
                .getSingleResult();
    }

    private long joinedCount(Long groupId) {
        return entityManager.createQuery(
                        "select count(m) from GroupMember m where m.routineGroup.id = :g and m.status = :s",
                        Long.class)
                .setParameter("g", groupId)
                .setParameter("s", GroupMemberStatus.JOINED)
                .getSingleResult();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private record Outcome(boolean succeeded, RuntimeException failure) {
    }

    private record Fixture(Long groupId, Long firstJoinerId, Long secondJoinerId) {
    }
}

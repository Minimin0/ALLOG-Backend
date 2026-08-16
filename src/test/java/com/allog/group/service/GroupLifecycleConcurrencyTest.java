package com.allog.group.service;

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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle commands racing on one group, against a real MySQL.
 *
 * <p>A separate class from {@code RoutineGroupJoinConcurrencyTest} on purpose: CI asserts that suite
 * runs exactly its own tests against InnoDB, and adding cases there would move a number the pipeline
 * checks. Enable the same way, by pointing GROUP_JOIN_CONCURRENCY_DB_URL at a MySQL 8.
 */
@EnabledIfEnvironmentVariable(named = "GROUP_JOIN_CONCURRENCY_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${GROUP_JOIN_CONCURRENCY_DB_URL}",
        "spring.datasource.username=${GROUP_JOIN_CONCURRENCY_DB_USERNAME}",
        "spring.datasource.password=${GROUP_JOIN_CONCURRENCY_DB_PASSWORD}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@ActiveProfiles("test")
class GroupLifecycleConcurrencyTest {

    private static final Instant JOINED_AT = Instant.parse("2026-08-10T00:00:00Z");

    @Autowired
    private RoutineGroupJoinService joinService;

    @Autowired
    private MembershipLifecycleService membershipLifecycleService;

    @Autowired
    private RoutineGroupLifecycleReconciler reconciler;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    /**
     * One free slot, one member leaving and one joining at the same time. The group row serialises
     * them, so whichever order the database picks, capacity still holds.
     */
    @RepeatedTest(8)
    void aDepartureAndTheLastJoinCannotBothOverfillTheRoom() throws Exception {
        Fixture fixture = fixture(3);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> leaving = executor.submit(attempt(barrier,
                    () -> membershipLifecycleService.leave(fixture.groupId(), fixture.memberUserId())));
            Future<Object> joining = executor.submit(attempt(barrier,
                    () -> joinService.join(fixture.groupId(), fixture.outsiderUserId())));
            leaving.get();
            joining.get();
        } finally {
            executor.shutdownNow();
        }

        inTransaction(() -> {
            RoutineGroup group = entityManager.find(RoutineGroup.class, fixture.groupId());
            long occupying = count(fixture.groupId(), GroupMemberStatus.JOINED)
                    + count(fixture.groupId(), GroupMemberStatus.ACTIVE);
            assertTrue(occupying <= group.getMaxMembers(),
                    () -> "capacity exceeded: " + occupying + " of " + group.getMaxMembers());
            assertEquals(0, count(fixture.groupId(), GroupMemberStatus.REMOVED));
            return null;
        });
    }

    /** Two reconcilers on one group: the second finds the work already committed. */
    @RepeatedTest(8)
    void twoReconcilersLeaveExactlyOneActivation() throws Exception {
        Fixture fixture = fixture(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(attempt(barrier,
                    () -> reconciler.reconcile(fixture.groupId())));
            Future<Object> second = executor.submit(attempt(barrier,
                    () -> reconciler.reconcile(fixture.groupId())));
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        inTransaction(() -> {
            RoutineGroup group = entityManager.find(RoutineGroup.class, fixture.groupId());
            assertEquals(RoutineGroupStatus.ACTIVE, group.getStatus());
            List<Instant> startedAt = entityManager.createQuery(
                            "select m.participationStartedAt from GroupMember m"
                                    + " where m.routineGroup.id = :g", Instant.class)
                    .setParameter("g", fixture.groupId())
                    .getResultList();
            assertEquals(2, startedAt.size());
            assertEquals(1, Set.copyOf(startedAt).size(), "one activation, one timestamp");
            return null;
        });
    }

    private Callable<Object> attempt(CyclicBarrier barrier, Runnable action) {
        return () -> {
            barrier.await();
            try {
                action.run();
                return "ok";
            } catch (RuntimeException failure) {
                return failure;
            }
        };
    }

    private long count(Long groupId, GroupMemberStatus status) {
        return entityManager.createQuery(
                        "select count(m) from GroupMember m where m.routineGroup.id = :g and m.status = :s",
                        Long.class)
                .setParameter("g", groupId)
                .setParameter("s", status)
                .getSingleResult();
    }

    /** A room of {@code maxMembers} holding two members, so exactly one slot is left when it is 3. */
    private Fixture fixture(int maxMembers) {
        return inTransaction(() -> {
            entityManager.createQuery("delete from Verification").executeUpdate();
            entityManager.createQuery("delete from RoutineSchedule").executeUpdate();
            entityManager.createQuery("delete from GroupMember").executeUpdate();
            entityManager.createQuery("delete from RoutineGroup").executeUpdate();

            User owner = User.create();
            User memberUser = User.create();
            User outsider = User.create();
            RoutineDefinition definition = new RoutineDefinition("물 마시기", null);
            entityManager.persist(owner);
            entityManager.persist(memberUser);
            entityManager.persist(outsider);
            entityManager.persist(definition);

            RoutineGroup group = new RoutineGroup(
                    definition, owner, "아침 물 마시기", GroupVisibility.PUBLIC,
                    maxMembers == 2 ? RoutineGroupStatus.FULL : RoutineGroupStatus.RECRUITING,
                    maxMembers, 1);
            entityManager.persist(group);
            entityManager.persist(new RoutineSchedule(
                    group, ScheduleType.DAILY,
                    LocalDate.now().minusDays(1), LocalDate.now().plusYears(1),
                    LocalTime.of(23, 0), "Asia/Seoul", Set.of()));
            entityManager.persist(new GroupMember(
                    group, owner, GroupMemberRole.OWNER, GroupMemberStatus.JOINED, JOINED_AT));
            GroupMember member = new GroupMember(
                    group, memberUser, GroupMemberRole.MEMBER, GroupMemberStatus.JOINED, JOINED_AT);
            entityManager.persist(member);
            entityManager.flush();

            return new Fixture(group.getId(), memberUser.getId(), outsider.getId());
        });
    }

    private <T> T inTransaction(Supplier<T> action) {
        if (transaction == null) {
            transaction = new TransactionTemplate(transactionManager);
        }
        return transaction.execute(status -> action.get());
    }

    private record Fixture(Long groupId, Long memberUserId, Long outsiderUserId) {
    }
}

package com.allog.group.service;
import com.allog.heart.domain.HeartLedgerEntry;
import com.allog.heart.domain.HeartTransactionType;
import com.allog.heart.domain.HeartWallet;
import com.allog.heart.service.HeartAccountService;

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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Writing down how a finished run turned out.
 *
 * <p>The verifications are real rows, because the outcome has to come from the same calculator the
 * member's own screen uses. The clock is pinned either side of the schedule's final deadline.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(GroupFinalizationIntegrationTest.MovableClockConfig.class)
class GroupFinalizationIntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 8, 10);
    private static final LocalDate END = LocalDate.of(2026, 8, 12);
    /** 23:00 Asia/Seoul on the last scheduled day, in UTC. */
    private static final Instant FINAL_DEADLINE = Instant.parse("2026-08-12T14:00:00Z");
    private static final Instant DURING_RUN = Instant.parse("2026-08-11T00:00:00Z");
    private static final Instant AFTER_RUN = FINAL_DEADLINE.plusSeconds(60);
    private static final Instant PARTICIPATION_START = Instant.parse("2026-08-10T00:00:00Z");
    private static final int REQUIRED_COMPLETIONS = 2;

    static MovableClock clock;

    @Autowired
    private RoutineGroupLifecycleReconciler reconciler;
    @Autowired
    private HeartAccountService heartAccountService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    // These tests commit, so they clear up on both sides: a leftover group or verification would
    // show up in another suite's counts.
    @BeforeEach
    @AfterEach
    void clearGroupTables() {
        inTransaction(() -> {
            entityManager.createQuery("delete from Verification").executeUpdate();
            entityManager.createQuery("delete from HeartLedgerEntry").executeUpdate();
            entityManager.createQuery("delete from HeartWallet").executeUpdate();
            entityManager.createQuery("delete from RoutineSchedule").executeUpdate();
            entityManager.createQuery("delete from GroupMember").executeUpdate();
            entityManager.createQuery("delete from RoutineGroup").executeUpdate();
            return null;
        });
    }

    @Test
    void nothingIsFinalisedWhileTheScheduleIsStillRunning() {
        Fixture fixture = fixture(2, 0);
        clock.moveTo(DURING_RUN);

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.ACTIVE, group(fixture.groupId()).getStatus());
            assertEquals(GroupMemberStatus.ACTIVE, member(fixture.firstMemberId()).getStatus());
            return null;
        });
    }

    /**
     * A verification still waiting on the AI or an operator holds the whole group open. Finalising
     * around it would decide someone's result before their evidence was read.
     */
    @Test
    void aPendingDecisionHoldsTheGroupOpenPastTheDeadline() {
        Fixture fixture = fixture(1, 1);
        clock.moveTo(AFTER_RUN);

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.ACTIVE, group(fixture.groupId()).getStatus());
            assertEquals(GroupMemberStatus.ACTIVE, member(fixture.firstMemberId()).getStatus());
            assertEquals(GroupMemberStatus.ACTIVE, member(fixture.secondMemberId()).getStatus());
            return null;
        });
    }

    @Test
    void aMemberWhoReachedTheGoalIsCompletedAndOneWhoDidNotIsFailed() {
        Fixture fixture = fixture(REQUIRED_COMPLETIONS, 0);
        clock.moveTo(AFTER_RUN);

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            GroupMember reached = member(fixture.firstMemberId());
            GroupMember missed = member(fixture.secondMemberId());
            assertEquals(GroupMemberStatus.COMPLETED, reached.getStatus());
            assertEquals(GroupMemberStatus.FAILED, missed.getStatus());
            // A finalised member still took part: the start timestamp is the participation contract.
            assertNotNull(reached.getParticipationStartedAt());
            assertNotNull(missed.getParticipationStartedAt());
            assertEquals(3, heartAccountService.balanceOf(fixture.firstUserId()));
            assertEquals(2, heartAccountService.balanceOf(fixture.secondUserId()));
            return null;
        });
    }

    /** The group ending is not everyone winning: both outcomes live under one COMPLETED group. */
    @Test
    void theGroupCompletesEvenWhenItsMembersDidNotAllSucceed() {
        Fixture fixture = fixture(REQUIRED_COMPLETIONS, 0);
        clock.moveTo(AFTER_RUN);

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.COMPLETED, group(fixture.groupId()).getStatus());
            return null;
        });
    }

    @Test
    void everybodyFailsWhenNobodyReachedTheGoal() {
        Fixture fixture = fixture(0, 0);
        clock.moveTo(AFTER_RUN);

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.COMPLETED, group(fixture.groupId()).getStatus());
            assertEquals(GroupMemberStatus.FAILED, member(fixture.firstMemberId()).getStatus());
            assertEquals(GroupMemberStatus.FAILED, member(fixture.secondMemberId()).getStatus());
            assertEquals(2, heartAccountService.balanceOf(fixture.firstUserId()));
            assertEquals(2, heartAccountService.balanceOf(fixture.secondUserId()));
            return null;
        });
    }

    @Test
    void finalisingAnAlreadyCompletedGroupChangesNothing() {
        Fixture fixture = fixture(REQUIRED_COMPLETIONS, 0);
        clock.moveTo(AFTER_RUN);
        reconciler.reconcile(fixture.groupId());

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.COMPLETED, group(fixture.groupId()).getStatus());
            assertEquals(GroupMemberStatus.COMPLETED, member(fixture.firstMemberId()).getStatus());
            assertEquals(GroupMemberStatus.FAILED, member(fixture.secondMemberId()).getStatus());
            assertEquals(3, heartAccountService.balanceOf(fixture.firstUserId()));
            assertEquals(2, heartAccountService.balanceOf(fixture.secondUserId()));
            return null;
        });
    }

    /**
     * @param approvedForFirstMember how many days the first member got approved
     * @param pendingForSecondMember submitted-but-undecided days for the second member
     */
    private Fixture fixture(int approvedForFirstMember, int pendingForSecondMember) {
        return inTransaction(() -> {
            User owner = User.create();
            User other = User.create();
            RoutineDefinition definition = new RoutineDefinition("물 마시기", null);
            entityManager.persist(owner);
            entityManager.persist(other);
            entityManager.persist(definition);

            RoutineGroup group = new RoutineGroup(
                    definition, owner, "아침 물 마시기", GroupVisibility.PUBLIC,
                    RoutineGroupStatus.ACTIVE, 2, REQUIRED_COMPLETIONS);
            entityManager.persist(group);
            RoutineSchedule schedule = new RoutineSchedule(
                    group, ScheduleType.DAILY, START, END, LocalTime.of(23, 0), "Asia/Seoul", Set.of());
            entityManager.persist(schedule);

            GroupMember first = new GroupMember(
                    group, owner, GroupMemberRole.OWNER, GroupMemberStatus.JOINED, PARTICIPATION_START);
            GroupMember second = new GroupMember(
                    group, other, GroupMemberRole.MEMBER, GroupMemberStatus.JOINED, PARTICIPATION_START);
            first.startParticipation(PARTICIPATION_START);
            second.startParticipation(PARTICIPATION_START);
            entityManager.persist(first);
            entityManager.persist(second);
            entityManager.flush();
            entityManager.persist(HeartWallet.openWith(owner, 2));
            entityManager.persist(HeartWallet.openWith(other, 2));
            entityManager.persist(HeartLedgerEntry.record(owner, HeartTransactionType.GROUP_JOIN_SPEND, 1, first.getId()));
            entityManager.persist(HeartLedgerEntry.record(other, HeartTransactionType.GROUP_JOIN_SPEND, 1, second.getId()));

            for (int day = 0; day < approvedForFirstMember; day++) {
                approve(first, schedule, START.plusDays(day));
            }
            for (int day = 0; day < pendingForSecondMember; day++) {
                submitWithoutDecision(second, schedule, START.plusDays(day));
            }
            entityManager.flush();
            return new Fixture(group.getId(), first.getId(), second.getId(), owner.getId(), other.getId());
        });
    }

    private void approve(GroupMember member, RoutineSchedule schedule, LocalDate date) {
        Clock at = Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        Verification verification = Verification.create(member, schedule, date);
        verification.submit(at);
        verification.approve(at);
        entityManager.persist(verification);
    }

    private void submitWithoutDecision(GroupMember member, RoutineSchedule schedule, LocalDate date) {
        Clock at = Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        Verification verification = Verification.create(member, schedule, date);
        verification.submit(at);
        entityManager.persist(verification);
    }

    private RoutineGroup group(Long groupId) {
        return entityManager.find(RoutineGroup.class, groupId);
    }

    private GroupMember member(Long memberId) {
        return entityManager.find(GroupMember.class, memberId);
    }

    private <T> T inTransaction(Supplier<T> action) {
        if (transaction == null) {
            transaction = new TransactionTemplate(transactionManager);
        }
        return transaction.execute(status -> action.get());
    }

    private record Fixture(Long groupId, Long firstMemberId, Long secondMemberId, Long firstUserId, Long secondUserId) {
    }

    /** Lets a test stand either side of the final deadline without waiting for it. */
    static final class MovableClock extends Clock {

        private volatile Instant now = DURING_RUN;

        void moveTo(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableClockConfig {

        @Bean
        @Primary
        Clock groupFinalizationTestClock() {
            clock = new MovableClock();
            return clock;
        }
    }
}

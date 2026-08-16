package com.allog.group.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.heart.domain.HeartLedgerEntry;
import com.allog.heart.domain.HeartTransactionType;
import com.allog.heart.domain.HeartWallet;
import com.allog.heart.repository.HeartLedgerEntryRepository;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
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
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Leave, cancellation and schedule-driven expiry against a real database.
 *
 * <p>The clock is pinned rather than slept on: every boundary here is a schedule boundary, and
 * waiting for real time would make the suite slow and flaky at once.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(GroupLifecycleIntegrationTest.FixedClockConfig.class)
class GroupLifecycleIntegrationTest {

    /** Well before the schedule below ends, so a group started here can still reach its goal. */
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final LocalDate SCHEDULE_START = LocalDate.of(2026, 8, 10);
    private static final LocalDate SCHEDULE_END = LocalDate.of(2026, 8, 20);

    @Autowired
    private MembershipLifecycleService membershipLifecycleService;

    @Autowired
    private RoutineGroupLifecycleReconciler reconciler;

    @Autowired
    private HeartLedgerEntryRepository heartLedgerRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    private TransactionTemplate transaction() {
        if (transaction == null) {
            transaction = new TransactionTemplate(transactionManager);
        }
        return transaction;
    }

    // These tests commit, so they clear up on both sides: a leftover group or verification would
    // show up in another suite's counts.
    @BeforeEach
    @AfterEach
    void clearGroupTables() {
        inTransaction(() -> {
            entityManager.createQuery("delete from HeartLedgerEntry").executeUpdate();
            entityManager.createQuery("delete from HeartWallet").executeUpdate();
            entityManager.createQuery("delete from Verification").executeUpdate();
            entityManager.createQuery("delete from RoutineSchedule").executeUpdate();
            entityManager.createQuery("delete from GroupMember").executeUpdate();
            entityManager.createQuery("delete from RoutineGroup").executeUpdate();
            return null;
        });
    }

    @Test
    void aMemberLeavesBeforeTheStartAndTheRoomRecruitsAgain() {
        Fixture fixture = fullGroup();

        membershipLifecycleService.leave(fixture.groupId(), fixture.memberUserId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.RECRUITING, group(fixture.groupId()).getStatus());
            assertEquals(GroupMemberStatus.LEFT, member(fixture.memberId()).getStatus());
            assertNull(member(fixture.memberId()).getParticipationStartedAt());
            return null;
        });
    }

    /** A retried request from a phone that lost the answer must not read as a failure. */
    @Test
    void leavingTwiceIsANoOp() {
        Fixture fixture = fullGroup();
        membershipLifecycleService.leave(fixture.groupId(), fixture.memberUserId());

        membershipLifecycleService.leave(fixture.groupId(), fixture.memberUserId());

        inTransaction(() -> {
            assertEquals(GroupMemberStatus.LEFT, member(fixture.memberId()).getStatus());
            assertEquals(RoutineGroupStatus.RECRUITING, group(fixture.groupId()).getStatus());
            return null;
        });
    }

    @Test
    void theOwnerCancelsRatherThanLeaving() {
        Fixture fixture = fullGroup();

        GroupLifecycleException thrown = assertThrows(GroupLifecycleException.class,
                () -> membershipLifecycleService.leave(fixture.groupId(), fixture.ownerUserId()));

        assertEquals(GroupLifecycleException.Reason.OWNER_MUST_CANCEL, thrown.reason());
    }

    /** Walking out of a running group is a policy nobody has decided, so it is refused. */
    @Test
    void aRunningGroupCannotBeLeft() {
        Fixture fixture = activeGroup();

        GroupLifecycleException thrown = assertThrows(GroupLifecycleException.class,
                () -> membershipLifecycleService.leave(fixture.groupId(), fixture.memberUserId()));

        assertEquals(GroupLifecycleException.Reason.NOT_LEAVABLE, thrown.reason());
        inTransaction(() -> {
            assertEquals(GroupMemberStatus.ACTIVE, member(fixture.memberId()).getStatus());
            return null;
        });
    }

    @Test
    void leavingRequiresAMembership() {
        Fixture fixture = fullGroup();
        Long stranger = inTransaction(() -> {
            User user = User.create();
            entityManager.persist(user);
            return user.getId();
        });

        GroupLifecycleException thrown = assertThrows(GroupLifecycleException.class,
                () -> membershipLifecycleService.leave(fixture.groupId(), stranger));

        assertEquals(GroupLifecycleException.Reason.MEMBERSHIP_NOT_FOUND, thrown.reason());
    }

    @Test
    void theOwnerCancelsAndEveryoneStillHoldingAPlaceIsRemoved() {
        Fixture fixture = fullGroup();

        membershipLifecycleService.cancel(fixture.groupId(), fixture.ownerUserId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.CANCELLED, group(fixture.groupId()).getStatus());
            assertEquals(GroupMemberStatus.REMOVED, member(fixture.ownerId()).getStatus());
            assertEquals(GroupMemberStatus.REMOVED, member(fixture.memberId()).getStatus());
            assertNull(member(fixture.memberId()).getParticipationStartedAt());
            return null;
        });
    }

    /** Someone who already walked away keeps that: it is what actually happened to them. */
    @Test
    void cancellationLeavesAnEarlierDepartureAlone() {
        Fixture fixture = fullGroup();
        membershipLifecycleService.leave(fixture.groupId(), fixture.memberUserId());

        membershipLifecycleService.cancel(fixture.groupId(), fixture.ownerUserId());

        inTransaction(() -> {
            assertEquals(GroupMemberStatus.LEFT, member(fixture.memberId()).getStatus());
            assertEquals(GroupMemberStatus.REMOVED, member(fixture.ownerId()).getStatus());
            return null;
        });
    }

    @Test
    void cancellingTwiceIsANoOp() {
        Fixture fixture = fullGroup();
        membershipLifecycleService.cancel(fixture.groupId(), fixture.ownerUserId());

        membershipLifecycleService.cancel(fixture.groupId(), fixture.ownerUserId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.CANCELLED, group(fixture.groupId()).getStatus());
            return null;
        });
    }

    @Test
    void onlyTheOwnerCancelsAndOthersCannotEvenSeeIt() {
        Fixture fixture = fullGroup();

        GroupLifecycleException thrown = assertThrows(GroupLifecycleException.class,
                () -> membershipLifecycleService.cancel(fixture.groupId(), fixture.memberUserId()));

        assertEquals(GroupLifecycleException.Reason.GROUP_NOT_FOUND, thrown.reason());
        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.FULL, group(fixture.groupId()).getStatus());
            return null;
        });
    }

    @Test
    void aRunningGroupCannotBeCancelled() {
        Fixture fixture = activeGroup();

        GroupLifecycleException thrown = assertThrows(GroupLifecycleException.class,
                () -> membershipLifecycleService.cancel(fixture.groupId(), fixture.ownerUserId()));

        assertEquals(GroupLifecycleException.Reason.NOT_CANCELLABLE, thrown.reason());
    }

    /** Plenty of schedule left, so the group keeps recruiting - there is no timeout to run out of. */
    @Test
    void aRecruitingGroupWithScheduleLeftIsUntouched() {
        Fixture fixture = recruitingGroup();

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.RECRUITING, group(fixture.groupId()).getStatus());
            assertEquals(GroupMemberStatus.JOINED, member(fixture.ownerId()).getStatus());
            return null;
        });
    }

    /**
     * The schedule, not a stopwatch, decides. Once too few opportunities remain to reach the goal,
     * the group can never start, so it expires and releases everyone still holding a place.
     */
    @Test
    void aGroupExpiresWhenItsScheduleCanNoLongerReachTheGoal() {
        Fixture fixture = recruitingGroupEndingSoon();

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.EXPIRED, group(fixture.groupId()).getStatus());
            assertEquals(GroupMemberStatus.REMOVED, member(fixture.ownerId()).getStatus());
            assertNull(member(fixture.ownerId()).getParticipationStartedAt());
            return null;
        });
    }

    @Test
    void expiryLeavesAnEarlierDepartureAlone() {
        Fixture fixture = fullGroupEndingSoon();
        membershipLifecycleService.leave(fixture.groupId(), fixture.memberUserId());

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.EXPIRED, group(fixture.groupId()).getStatus());
            assertEquals(GroupMemberStatus.LEFT, member(fixture.memberId()).getStatus());
            assertEquals(GroupMemberStatus.REMOVED, member(fixture.ownerId()).getStatus());
            return null;
        });
    }

    /** A room left FULL by an older path still starts, rather than being stranded forever. */
    @Test
    void reconcilingAFullRoomStartsIt() {
        Fixture fixture = fullGroup();

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            RoutineGroup group = group(fixture.groupId());
            GroupMember owner = member(fixture.ownerId());
            GroupMember member = member(fixture.memberId());
            assertEquals(RoutineGroupStatus.ACTIVE, group.getStatus());
            assertEquals(GroupMemberStatus.ACTIVE, owner.getStatus());
            assertEquals(GroupMemberStatus.ACTIVE, member.getStatus());
            assertEquals(NOW, owner.getParticipationStartedAt());
            assertEquals(owner.getParticipationStartedAt(), member.getParticipationStartedAt(),
                    "every participant starts at the same instant");
            return null;
        });
    }

    /** A second reconciler finds the work already done rather than repeating it. */
    @Test
    void reconcilingTwiceChangesNothingTheSecondTime() {
        Fixture fixture = fullGroup();
        reconciler.reconcile(fixture.groupId());
        Instant startedAt = inTransaction(() -> member(fixture.ownerId()).getParticipationStartedAt());

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.ACTIVE, group(fixture.groupId()).getStatus());
            assertEquals(startedAt, member(fixture.ownerId()).getParticipationStartedAt());
            return null;
        });
    }

    @Test
    void reconcilingATerminalGroupDoesNothing() {
        Fixture fixture = fullGroup();
        membershipLifecycleService.cancel(fixture.groupId(), fixture.ownerUserId());

        reconciler.reconcile(fixture.groupId());

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.CANCELLED, group(fixture.groupId()).getStatus());
            return null;
        });
    }

    /** Lifecycle records what happened; paying anyone back is M3-C's job and must not start here. */
    @Test
    void preStartLifecycleCommandsRefundTheOriginalSpends() {
        Fixture fixture = fullGroup();

        membershipLifecycleService.leave(fixture.groupId(), fixture.memberUserId());
        membershipLifecycleService.cancel(fixture.groupId(), fixture.ownerUserId());
        reconciler.reconcile(fixture.groupId());

        assertEquals(4, heartLedgerRepository.count(), "both debits and their exact refunds are audited");
    }

    private Fixture fullGroup() {
        return fixture(RoutineGroupStatus.FULL, 2, SCHEDULE_END, false);
    }

    private Fixture activeGroup() {
        return fixture(RoutineGroupStatus.FULL, 2, SCHEDULE_END, true);
    }

    private Fixture recruitingGroup() {
        return fixture(RoutineGroupStatus.RECRUITING, 5, SCHEDULE_END, false);
    }

    /** Ends today, so only today's opportunity remains and a two-day goal is already out of reach. */
    private Fixture recruitingGroupEndingSoon() {
        return fixture(RoutineGroupStatus.RECRUITING, 5, SCHEDULE_START, false);
    }

    private Fixture fullGroupEndingSoon() {
        return fixture(RoutineGroupStatus.FULL, 2, SCHEDULE_START, false);
    }

    private Fixture fixture(
            RoutineGroupStatus groupStatus,
            int maxMembers,
            LocalDate scheduleEnd,
            boolean started
    ) {
        return inTransaction(() -> {
            User owner = User.create();
            User memberUser = User.create();
            RoutineDefinition definition = new RoutineDefinition("물 마시기", null);
            entityManager.persist(owner);
            entityManager.persist(memberUser);
            entityManager.persist(definition);

            RoutineGroup group = new RoutineGroup(
                    definition, owner, "아침 물 마시기", GroupVisibility.PUBLIC,
                    started ? RoutineGroupStatus.ACTIVE : groupStatus, maxMembers, 2);
            entityManager.persist(group);
            entityManager.persist(new RoutineSchedule(
                    group, ScheduleType.DAILY, SCHEDULE_START, scheduleEnd,
                    LocalTime.of(23, 0), "Asia/Seoul", Set.of()));

            GroupMember ownerMember = new GroupMember(
                    group, owner, GroupMemberRole.OWNER, GroupMemberStatus.JOINED, NOW.minusSeconds(60));
            GroupMember member = new GroupMember(
                    group, memberUser, GroupMemberRole.MEMBER, GroupMemberStatus.JOINED, NOW.minusSeconds(30));
            if (started) {
                ownerMember.startParticipation(NOW.minusSeconds(10));
                member.startParticipation(NOW.minusSeconds(10));
            }
            entityManager.persist(ownerMember);
            entityManager.persist(member);
            entityManager.flush();
            entityManager.persist(HeartWallet.openWith(owner, 2));
            entityManager.persist(HeartWallet.openWith(memberUser, 2));
            entityManager.persist(HeartLedgerEntry.record(
                    owner, HeartTransactionType.GROUP_JOIN_SPEND, 1, ownerMember.getId()));
            entityManager.persist(HeartLedgerEntry.record(
                    memberUser, HeartTransactionType.GROUP_JOIN_SPEND, 1, member.getId()));
            entityManager.flush();

            return new Fixture(
                    group.getId(), owner.getId(), memberUser.getId(),
                    ownerMember.getId(), member.getId());
        });
    }

    private RoutineGroup group(Long groupId) {
        return entityManager.find(RoutineGroup.class, groupId);
    }

    private GroupMember member(Long memberId) {
        return entityManager.find(GroupMember.class, memberId);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction().execute(status -> action.get());
    }

    private record Fixture(
            Long groupId,
            Long ownerUserId,
            Long memberUserId,
            Long ownerId,
            Long memberId
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock groupLifecycleTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

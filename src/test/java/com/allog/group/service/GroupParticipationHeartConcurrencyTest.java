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
import com.allog.heart.repository.HeartWalletRepository;
import com.allog.heart.service.InsufficientHeartsException;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import jakarta.persistence.EntityManager;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "GROUP_JOIN_CONCURRENCY_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${GROUP_JOIN_CONCURRENCY_DB_URL}",
        "spring.datasource.username=${GROUP_JOIN_CONCURRENCY_DB_USERNAME}",
        "spring.datasource.password=${GROUP_JOIN_CONCURRENCY_DB_PASSWORD}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@ActiveProfiles("test")
class GroupParticipationHeartConcurrencyTest {

    @Autowired
    private RoutineGroupJoinService joinService;
    @Autowired
    private MembershipLifecycleService membershipLifecycleService;
    @Autowired
    private RoutineGroupLifecycleReconciler reconciler;
    @Autowired
    private HeartWalletRepository walletRepository;
    @Autowired
    private HeartLedgerEntryRepository ledgerRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clearTables() {
        transaction = new TransactionTemplate(transactionManager);
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
    void twoDifferentGroupJoinsWithOneHeartProduceOneDebit() throws Exception {
        User payer = userWithWallet(1);
        Long firstGroupId = recruitingGroupWithOwner(3).groupId();
        Long secondGroupId = recruitingGroupWithOwner(3).groupId();

        List<Outcome> outcomes = race(
                () -> joinService.join(firstGroupId, payer.getId()),
                () -> joinService.join(secondGroupId, payer.getId()));

        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count());
        outcomes.stream().filter(outcome -> !outcome.succeeded()).forEach(outcome ->
                assertInstanceOf(InsufficientHeartsException.class, outcome.failure()));
        inTransaction(() -> {
            assertEquals(0, balance(payer.getId()));
            assertEquals(1L, count("select count(m) from GroupMember m where m.user.id = :userId", payer.getId()));
            assertEquals(1L, spendCountForUser(payer.getId()));
            return null;
        });
    }

    @Test
    void insufficientHeartOnLastSlotLeavesGroupRecruitingAndUnpaid() {
        Seed seed = recruitingGroupWithOwner(2);
        User joiner = userWithWallet(0);

        assertThrows(InsufficientHeartsException.class, () -> joinService.join(seed.groupId(), joiner.getId()));

        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.RECRUITING, entityManager.find(RoutineGroup.class, seed.groupId()).getStatus());
            assertFalse(hasMembership(seed.groupId(), joiner.getId()));
            assertEquals(0, balance(joiner.getId()));
            return null;
        });
    }

    @Test
    void leaveAndJoinSerializeOnTheGroupLockWithoutOverCapacityOrDoubleRefund() throws Exception {
        Seed seed = groupWithPaidMember(3, LocalDate.now().minusDays(1), LocalDate.now().plusYears(1));
        User joiner = userWithWallet(3);

        List<Outcome> outcomes = race(
                () -> membershipLifecycleService.leave(seed.groupId(), seed.memberUserId()),
                () -> joinService.join(seed.groupId(), joiner.getId()));

        outcomes.forEach(outcome -> assertFalse(outcome.failure() instanceof IllegalStateException));
        inTransaction(() -> {
            long occupying = count("select count(m) from GroupMember m where m.routineGroup.id = :groupId and m.status in ('JOINED', 'ACTIVE')", seed.groupId());
            assertTrue(occupying <= 3);
            assertTrue(refundCount(seed.memberId()) <= 1);
            assertTrue(spendCountForUser(joiner.getId()) <= 1);
            return null;
        });
    }

    @Test
    void cancelAndJoinLeaveNoUnpaidOrPartiallyRefundedParticipant() throws Exception {
        Seed seed = groupWithPaidMember(4, LocalDate.now().minusDays(1), LocalDate.now().plusYears(1));
        User joiner = userWithWallet(3);

        List<Outcome> outcomes = race(
                () -> membershipLifecycleService.cancel(seed.groupId(), seed.ownerUserId()),
                () -> joinService.join(seed.groupId(), joiner.getId()));

        outcomes.forEach(outcome -> assertFalse(outcome.failure() instanceof IllegalStateException));
        inTransaction(() -> {
            RoutineGroup group = entityManager.find(RoutineGroup.class, seed.groupId());
            assertEquals(RoutineGroupStatus.CANCELLED, group.getStatus());
            List<GroupMember> removed = entityManager.createQuery(
                            "select m from GroupMember m where m.routineGroup.id = :groupId and m.status = :status", GroupMember.class)
                    .setParameter("groupId", seed.groupId())
                    .setParameter("status", GroupMemberStatus.REMOVED)
                    .getResultList();
            for (GroupMember member : removed) {
                assertEquals(1, refundCount(member.getId()));
            }
            assertEquals(0L, count("select count(m) from GroupMember m where m.routineGroup.id = :groupId and m.status = 'JOINED'", seed.groupId()));
            return null;
        });
    }

    @Test
    void twoExpiryReconcilersRefundEachPaidMembershipOnlyOnce() throws Exception {
        Seed seed = groupWithPaidMember(3, LocalDate.now().minusDays(2), LocalDate.now().minusDays(1));

        List<Outcome> outcomes = race(
                () -> reconciler.reconcile(seed.groupId()),
                () -> reconciler.reconcile(seed.groupId()));

        outcomes.forEach(outcome -> assertTrue(outcome.succeeded()));
        inTransaction(() -> {
            assertEquals(RoutineGroupStatus.EXPIRED, entityManager.find(RoutineGroup.class, seed.groupId()).getStatus());
            assertEquals(1, refundCount(seed.ownerMemberId()));
            assertEquals(1, refundCount(seed.memberId()));
            assertEquals(3, balance(seed.ownerUserId()));
            assertEquals(3, balance(seed.memberUserId()));
            return null;
        });
    }

    private User userWithWallet(int hearts) {
        return inTransaction(() -> {
            User user = User.create();
            entityManager.persist(user);
            entityManager.persist(HeartWallet.openWith(user, hearts));
            entityManager.flush();
            return user;
        });
    }

    private Seed recruitingGroupWithOwner(int maxMembers) {
        return groupWithPaidMember(maxMembers, LocalDate.now().minusDays(1), LocalDate.now().plusYears(1), false);
    }

    private Seed groupWithPaidMember(int maxMembers, LocalDate startDate, LocalDate endDate) {
        return groupWithPaidMember(maxMembers, startDate, endDate, true);
    }

    private Seed groupWithPaidMember(int maxMembers, LocalDate startDate, LocalDate endDate, boolean includeMember) {
        return inTransaction(() -> {
            User owner = User.create();
            User memberUser = User.create();
            RoutineDefinition definition = new RoutineDefinition("routine", "description");
            entityManager.persist(owner);
            entityManager.persist(memberUser);
            entityManager.persist(definition);
            RoutineGroup group = new RoutineGroup(
                    definition, owner, "group", GroupVisibility.PUBLIC, RoutineGroupStatus.RECRUITING, maxMembers, 1);
            entityManager.persist(group);
            entityManager.persist(new RoutineSchedule(
                    group, ScheduleType.DAILY, startDate, endDate, LocalTime.of(23, 0), "UTC", Set.of()));
            GroupMember ownerMember = new GroupMember(
                    group, owner, GroupMemberRole.OWNER, GroupMemberStatus.JOINED, Instant.now());
            entityManager.persist(ownerMember);
            GroupMember member = null;
            if (includeMember) {
                member = new GroupMember(
                        group, memberUser, GroupMemberRole.MEMBER, GroupMemberStatus.JOINED, Instant.now());
                entityManager.persist(member);
            }
            entityManager.flush();
            entityManager.persist(HeartWallet.openWith(owner, 2));
            entityManager.persist(HeartWallet.openWith(memberUser, 2));
            entityManager.persist(HeartLedgerEntry.record(
                    owner, HeartTransactionType.GROUP_JOIN_SPEND, 1, ownerMember.getId()));
            if (member != null) {
                entityManager.persist(HeartLedgerEntry.record(
                        memberUser, HeartTransactionType.GROUP_JOIN_SPEND, 1, member.getId()));
            }
            entityManager.flush();
            return new Seed(
                    group.getId(), owner.getId(), memberUser.getId(), ownerMember.getId(),
                    member == null ? null : member.getId());
        });
    }

    private List<Outcome> race(Runnable firstAction, Runnable secondAction) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(attempt(barrier, firstAction));
            Future<Outcome> second = executor.submit(attempt(barrier, secondAction));
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Outcome> attempt(CyclicBarrier barrier, Runnable action) {
        return () -> {
            barrier.await();
            try {
                action.run();
                return new Outcome(true, null);
            } catch (RuntimeException exception) {
                return new Outcome(false, exception);
            }
        };
    }

    private int balance(Long userId) {
        return walletRepository.findByUser_Id(userId).orElseThrow().getBalance();
    }

    private boolean hasMembership(Long groupId, Long userId) {
        return !entityManager.createQuery(
                        "select m from GroupMember m where m.routineGroup.id = :groupId and m.user.id = :userId", GroupMember.class)
                .setParameter("groupId", groupId)
                .setParameter("userId", userId)
                .getResultList().isEmpty();
    }

    private long count(String jpql, Long id) {
        var query = entityManager.createQuery(jpql, Long.class);
        if (jpql.contains(":userId")) {
            query.setParameter("userId", id);
        }
        if (jpql.contains(":groupId")) {
            query.setParameter("groupId", id);
        }
        return query.getSingleResult();
    }

    private int refundCount(Long memberId) {
        return ledgerRepository.findByTypeAndSourceId(HeartTransactionType.GROUP_JOIN_REFUND, memberId).isPresent() ? 1 : 0;
    }

    private long spendCountForUser(Long userId) {
        return entityManager.createQuery(
                        "select count(e) from HeartLedgerEntry e where e.user.id = :userId and e.type = :type", Long.class)
                .setParameter("userId", userId)
                .setParameter("type", HeartTransactionType.GROUP_JOIN_SPEND)
                .getSingleResult();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private record Outcome(boolean succeeded, RuntimeException failure) {
    }

    private record Seed(
            Long groupId,
            Long ownerUserId,
            Long memberUserId,
            Long ownerMemberId,
            Long memberId
    ) {
    }
}

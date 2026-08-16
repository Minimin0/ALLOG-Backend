package com.allog.group.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.dto.CreateRoutineGroupRequest;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.Supplier;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Import(GroupParticipationHeartIntegrationTest.FixedClockConfig.class)
class GroupParticipationHeartIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);
    private static final LocalDate FUTURE_END = LocalDate.of(2026, 8, 20);

    @Autowired
    private RoutineGroupCreationService creationService;
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
    void clearGroupAndHeartTables() {
        transaction = new TransactionTemplate(transactionManager);
        inTransaction(() -> {
            entityManager.createQuery("delete from Verification").executeUpdate();
            entityManager.createQuery("delete from HeartLedgerEntry").executeUpdate();
            entityManager.createQuery("delete from HeartWallet").executeUpdate();
            entityManager.createQuery("delete from RoutineSchedule").executeUpdate();
            entityManager.createQuery("delete from GroupMember").executeUpdate();
            entityManager.createQuery("delete from RoutineGroup").executeUpdate();
            entityManager.flush();
            return null;
        });
    }

    @Test
    void creationDebitsTheOwnerAndRecordsTheOwnerMembershipAsLedgerSource() {
        Participant owner = participant(3);

        Long groupId = creationService.create(owner.userId(), request(owner.definitionId(), 3, 1, TODAY, FUTURE_END));

        inTransaction(() -> {
            GroupMember ownerMember = membership(groupId, owner.userId());
            HeartLedgerEntry spend = spend(ownerMember.getId());
            assertEquals(2, balance(owner.userId()));
            assertEquals(-1, spend.getAmount());
            assertEquals(owner.userId(), spend.getUser().getId());
            assertEquals(ownerMember.getId(), spend.getSourceId());
            assertEquals(RoutineGroupStatus.RECRUITING, group(groupId).getStatus());
            return null;
        });
    }

    @Test
    void insufficientOwnerHeartsRollBackGroupMembershipScheduleAndDebit() {
        Participant owner = participant(0);

        assertThrows(InsufficientHeartsException.class,
                () -> creationService.create(owner.userId(), request(owner.definitionId(), 3, 1, TODAY, FUTURE_END)));

        inTransaction(() -> {
            assertEquals(0L, count("select count(g) from RoutineGroup g"));
            assertEquals(0L, count("select count(m) from GroupMember m"));
            assertEquals(0L, count("select count(s) from RoutineSchedule s"));
            assertEquals(0L, count("select count(e) from HeartLedgerEntry e"));
            assertEquals(0, balance(owner.userId()));
            return null;
        });
    }

    @Test
    void oneMemberGroupChargesOwnerBeforeBecomingActive() {
        Participant owner = participant(3);

        Long groupId = creationService.create(owner.userId(), request(owner.definitionId(), 1, 1, TODAY, FUTURE_END));

        inTransaction(() -> {
            GroupMember ownerMember = membership(groupId, owner.userId());
            assertEquals(RoutineGroupStatus.ACTIVE, group(groupId).getStatus());
            assertNotNull(ownerMember.getParticipationStartedAt());
            assertEquals(2, balance(owner.userId()));
            assertEquals(-1, spend(ownerMember.getId()).getAmount());
            return null;
        });
    }

    @Test
    void joinDebitsOnceAndUsesThePersistedMemberAsLedgerSource() {
        Participant owner = participant(3);
        Participant joiner = participant(3);
        Long groupId = creationService.create(owner.userId(), request(owner.definitionId(), 3, 1, TODAY, FUTURE_END));

        joinService.join(groupId, joiner.userId());

        inTransaction(() -> {
            GroupMember member = membership(groupId, joiner.userId());
            assertEquals(2, balance(joiner.userId()));
            assertEquals(-1, spend(member.getId()).getAmount());
            assertEquals(member.getId(), spend(member.getId()).getSourceId());
            return null;
        });
    }

    @Test
    void insufficientJoinLeavesNoMembershipSpendOrActivationEvenForTheLastSlot() {
        Participant owner = participant(3);
        Participant joiner = participant(0);
        Long groupId = creationService.create(owner.userId(), request(owner.definitionId(), 2, 1, TODAY, FUTURE_END));

        assertThrows(InsufficientHeartsException.class, () -> joinService.join(groupId, joiner.userId()));

        inTransaction(() -> {
            assertFalse(hasMembership(groupId, joiner.userId()));
            assertEquals(RoutineGroupStatus.RECRUITING, group(groupId).getStatus());
            assertEquals(0, balance(joiner.userId()));
            assertEquals(1L, count("select count(e) from HeartLedgerEntry e"));
            return null;
        });
    }

    @Test
    void duplicateJoinDoesNotCreateASecondDebit() {
        Participant owner = participant(3);
        Participant joiner = participant(3);
        Long groupId = creationService.create(owner.userId(), request(owner.definitionId(), 3, 1, TODAY, FUTURE_END));

        joinService.join(groupId, joiner.userId());
        assertThrows(RoutineGroupJoinException.class, () -> joinService.join(groupId, joiner.userId()));

        inTransaction(() -> {
            GroupMember member = membership(groupId, joiner.userId());
            assertEquals(2, balance(joiner.userId()));
            assertEquals(-1, spend(member.getId()).getAmount());
            assertEquals(2L, count("select count(e) from HeartLedgerEntry e where e.type = 'GROUP_JOIN_SPEND'"));
            return null;
        });
    }

    @Test
    void leaveRefundsTheOriginalDebitExactlyOnce() {
        Participant owner = participant(3);
        Participant joiner = participant(3);
        Long groupId = creationService.create(owner.userId(), request(owner.definitionId(), 3, 1, TODAY, FUTURE_END));
        joinService.join(groupId, joiner.userId());

        membershipLifecycleService.leave(groupId, joiner.userId());
        membershipLifecycleService.leave(groupId, joiner.userId());

        inTransaction(() -> {
            GroupMember member = membership(groupId, joiner.userId());
            assertEquals(GroupMemberStatus.LEFT, member.getStatus());
            assertEquals(3, balance(joiner.userId()));
            assertEquals(1, refundCount(member.getId()));
            assertEquals(3L, count("select count(e) from HeartLedgerEntry e"));
            return null;
        });
    }

    @Test
    void cancelRefundsAllJoinedParticipantsAndSkipsAnAlreadyLeftMember() {
        Participant owner = participant(3);
        Participant first = participant(3);
        Participant second = participant(3);
        Long groupId = creationService.create(owner.userId(), request(owner.definitionId(), 4, 1, TODAY, FUTURE_END));
        joinService.join(groupId, first.userId());
        joinService.join(groupId, second.userId());
        membershipLifecycleService.leave(groupId, first.userId());

        membershipLifecycleService.cancel(groupId, owner.userId());
        membershipLifecycleService.cancel(groupId, owner.userId());

        inTransaction(() -> {
            GroupMember ownerMember = membership(groupId, owner.userId());
            GroupMember firstMember = membership(groupId, first.userId());
            GroupMember secondMember = membership(groupId, second.userId());
            assertEquals(RoutineGroupStatus.CANCELLED, group(groupId).getStatus());
            assertEquals(GroupMemberStatus.REMOVED, ownerMember.getStatus());
            assertEquals(GroupMemberStatus.LEFT, firstMember.getStatus());
            assertEquals(GroupMemberStatus.REMOVED, secondMember.getStatus());
            assertEquals(3, balance(owner.userId()));
            assertEquals(3, balance(first.userId()));
            assertEquals(3, balance(second.userId()));
            assertEquals(1, refundCount(ownerMember.getId()));
            assertEquals(1, refundCount(firstMember.getId()));
            assertEquals(1, refundCount(secondMember.getId()));
            return null;
        });
    }
    @Test
    void expiryRefundsRemainingJoinedParticipantsAndLeavesPriorDepartureUntouched() {
        Participant owner = participant(3);
        Participant joiner = participant(3);
        Long groupId = creationService.create(owner.userId(), request(owner.definitionId(), 3, 1, TODAY.minusDays(1), TODAY.minusDays(1)));
        joinService.join(groupId, joiner.userId());
        membershipLifecycleService.leave(groupId, joiner.userId());

        reconciler.reconcile(groupId);
        reconciler.reconcile(groupId);

        inTransaction(() -> {
            GroupMember ownerMember = membership(groupId, owner.userId());
            GroupMember joinerMember = membership(groupId, joiner.userId());
            assertEquals(RoutineGroupStatus.EXPIRED, group(groupId).getStatus());
            assertEquals(GroupMemberStatus.REMOVED, ownerMember.getStatus());
            assertEquals(GroupMemberStatus.LEFT, joinerMember.getStatus());
            assertEquals(3, balance(owner.userId()));
            assertEquals(3, balance(joiner.userId()));
            assertEquals(1, refundCount(ownerMember.getId()));
            assertEquals(1, refundCount(joinerMember.getId()));
            return null;
        });
    }

    private Participant participant(int hearts) {
        return inTransaction(() -> {
            User user = User.create();
            RoutineDefinition definition = new RoutineDefinition("routine", "description");
            entityManager.persist(user);
            entityManager.persist(HeartWallet.openWith(user, hearts));
            entityManager.persist(definition);
            entityManager.flush();
            return new Participant(user.getId(), definition.getId());
        });
    }

    private CreateRoutineGroupRequest request(
            Long definitionId,
            int maxMembers,
            int requiredCompletions,
            LocalDate startDate,
            LocalDate endDate
    ) {
        return new CreateRoutineGroupRequest(
                definitionId,
                "heart group",
                GroupVisibility.PUBLIC,
                maxMembers,
                requiredCompletions,
                new CreateRoutineGroupRequest.Schedule(
                        ScheduleType.DAILY,
                        startDate,
                        endDate,
                        LocalTime.of(23, 0),
                        "UTC",
                        Set.of()
                ),
                null
        );
    }

    private GroupMember membership(Long groupId, Long userId) {
        return entityManager.createQuery(
                        "select m from GroupMember m where m.routineGroup.id = :groupId and m.user.id = :userId",
                        GroupMember.class)
                .setParameter("groupId", groupId)
                .setParameter("userId", userId)
                .getSingleResult();
    }

    private boolean hasMembership(Long groupId, Long userId) {
        return !entityManager.createQuery(
                        "select m from GroupMember m where m.routineGroup.id = :groupId and m.user.id = :userId",
                        GroupMember.class)
                .setParameter("groupId", groupId)
                .setParameter("userId", userId)
                .getResultList()
                .isEmpty();
    }

    private RoutineGroup group(Long groupId) {
        return entityManager.find(RoutineGroup.class, groupId);
    }

    private int balance(Long userId) {
        return walletRepository.findByUser_Id(userId).orElseThrow().getBalance();
    }

    private HeartLedgerEntry spend(Long memberId) {
        return ledgerRepository.findByTypeAndSourceId(HeartTransactionType.GROUP_JOIN_SPEND, memberId)
                .orElseThrow();
    }

    private int refundCount(Long memberId) {
        return ledgerRepository.findByTypeAndSourceId(HeartTransactionType.GROUP_JOIN_REFUND, memberId)
                .isPresent() ? 1 : 0;
    }

    private long count(String jpql) {
        return entityManager.createQuery(jpql, Long.class).getSingleResult();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private record Participant(Long userId, Long definitionId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock heartIntegrationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

package com.allog.heart.service;

import com.allog.heart.domain.HeartTransactionType;
import com.allog.heart.repository.HeartLedgerEntryRepository;
import com.allog.heart.repository.HeartWalletRepository;
import com.allog.user.domain.User;
import com.allog.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not {@code @Transactional}: the concurrency case needs two threads to see each other's commits, so
 * rows are cleared by hand on both sides.
 */
@SpringBootTest
@ActiveProfiles("test")
class HeartAccountServiceTest {

    private static final int GRANT = HeartAccountService.INITIAL_GRANT_AMOUNT;

    @Autowired
    private HeartAccountService heartAccountService;

    @Autowired
    private HeartWalletRepository walletRepository;

    @Autowired
    private HeartLedgerEntryRepository ledgerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clearHeartTables() {
        jdbcTemplate.update("DELETE FROM heart_ledger_entry");
        jdbcTemplate.update("DELETE FROM heart_wallet");
    }

    @Test
    void theRatifiedInitialGrantIsThree() {
        assertEquals(3, HeartAccountService.INITIAL_GRANT_AMOUNT);
    }

    @Test
    void grantOpensAWalletAndWritesItsLedgerEntry() {
        Long userId = newUserId();

        heartAccountService.grantInitialHearts(userId, 11L);

        assertEquals(GRANT, heartAccountService.balanceOf(userId));
        assertTrue(ledgerRepository.existsByTypeAndSourceId(HeartTransactionType.INITIAL_GRANT, 11L));
        assertReconciled(userId);
    }

    @Test
    void theSameProfileCannotBeGrantedTwice() {
        Long userId = newUserId();
        heartAccountService.grantInitialHearts(userId, 12L);

        assertThrows(DataIntegrityViolationException.class,
                () -> heartAccountService.grantInitialHearts(userId, 12L));

        assertEquals(GRANT, heartAccountService.balanceOf(userId));
        assertEquals(1, ledgerRows(userId));
    }

    @Test
    void spendDebitsTheWalletAndRecordsANegativeEntry() {
        Long userId = granted(21L);

        heartAccountService.spendForGroupJoin(userId, 100L, 1);

        assertEquals(GRANT - 1, heartAccountService.balanceOf(userId));
        assertEquals(-1, amountOf(HeartTransactionType.GROUP_JOIN_SPEND, 100L));
        assertReconciled(userId);
    }

    @Test
    void spendingMoreThanTheBalanceChangesNothing() {
        Long userId = granted(22L);

        assertThrows(InsufficientHeartsException.class,
                () -> heartAccountService.spendForGroupJoin(userId, 101L, GRANT + 1));

        assertEquals(GRANT, heartAccountService.balanceOf(userId));
        assertEquals(1, ledgerRows(userId));
        assertReconciled(userId);
    }

    @Test
    void aRepeatedJoinIsChargedOnce() {
        Long userId = granted(23L);
        heartAccountService.spendForGroupJoin(userId, 102L, 1);

        assertThrows(DataIntegrityViolationException.class,
                () -> heartAccountService.spendForGroupJoin(userId, 102L, 1));

        assertEquals(GRANT - 1, heartAccountService.balanceOf(userId));
        assertReconciled(userId);
    }

    @Test
    void refundReturnsWhatTheJoinCost() {
        Long userId = granted(24L);
        heartAccountService.spendForGroupJoin(userId, 103L, 1);

        heartAccountService.refundGroupJoin(userId, 103L, 1);

        assertEquals(GRANT, heartAccountService.balanceOf(userId));
        assertEquals(1, amountOf(HeartTransactionType.GROUP_JOIN_REFUND, 103L));
        assertReconciled(userId);
    }

    @Test
    void aJoinCannotBeRefundedTwice() {
        Long userId = granted(25L);
        heartAccountService.spendForGroupJoin(userId, 104L, 1);
        heartAccountService.refundGroupJoin(userId, 104L, 1);

        assertThrows(DataIntegrityViolationException.class,
                () -> heartAccountService.refundGroupJoin(userId, 104L, 1));

        assertEquals(GRANT, heartAccountService.balanceOf(userId));
        assertReconciled(userId);
    }

    /** Without this check a repeated cancellation would mint hearts out of nothing. */
    @Test
    void refusesToRefundAJoinThatWasNeverCharged() {
        Long userId = granted(26L);

        assertThrows(InvalidHeartOperationException.class,
                () -> heartAccountService.refundGroupJoin(userId, 105L, 1));

        assertEquals(GRANT, heartAccountService.balanceOf(userId));
        assertEquals(1, ledgerRows(userId));
    }

    @Test
    void aMissingWalletIsSurfacedRatherThanInvented() {
        Long userId = newUserId();

        assertThrows(HeartWalletNotFoundException.class, () -> heartAccountService.balanceOf(userId));
        assertThrows(HeartWalletNotFoundException.class,
                () -> heartAccountService.spendForGroupJoin(userId, 106L, 1));

        assertEquals(0, walletRepository.findByUser_Id(userId).stream().count(),
                "reading must not create a wallet");
    }

    @Test
    void rejectsNonPositiveAmounts() {
        Long userId = granted(27L);

        assertThrows(IllegalArgumentException.class,
                () -> heartAccountService.spendForGroupJoin(userId, 107L, 0));
        assertThrows(IllegalArgumentException.class,
                () -> heartAccountService.refundGroupJoin(userId, 107L, -1));
    }

    /**
     * One heart, two joins at once. The conditional debit is what decides this: a read-then-write
     * would let both threads see the same balance and both succeed.
     */
    @Test
    void twoConcurrentSpendsOnOneHeartLeaveExactlyOneDebit() throws Exception {
        Long userId = newUserId();
        heartAccountService.grantInitialHearts(userId, 28L);
        jdbcTemplate.update("UPDATE heart_wallet SET balance = 1 WHERE user_id = ?", userId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(spend(barrier, userId, 201L));
            Future<Object> second = executor.submit(spend(barrier, userId, 202L));
            List<Object> outcomes = List.of(first.get(), second.get());

            long succeeded = outcomes.stream().filter("ok"::equals).count();
            long refused = outcomes.stream().filter(InsufficientHeartsException.class::isInstance).count();

            assertEquals(1, succeeded, () -> "exactly one spend may win: " + outcomes);
            assertEquals(1, refused, () -> "the loser must be refused, not: " + outcomes);
            outcomes.stream().filter(Throwable.class::isInstance)
                    .forEach(o -> assertInstanceOf(InsufficientHeartsException.class, o));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, heartAccountService.balanceOf(userId), "balance must never go negative");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM heart_ledger_entry WHERE user_id = ? AND type = 'GROUP_JOIN_SPEND'",
                Integer.class, userId));
    }

    private Callable<Object> spend(CyclicBarrier barrier, Long userId, Long sourceId) {
        return () -> {
            barrier.await();
            try {
                heartAccountService.spendForGroupJoin(userId, sourceId, 1);
                return "ok";
            } catch (RuntimeException failure) {
                return failure;
            }
        };
    }

    private Long granted(Long profileId) {
        Long userId = newUserId();
        heartAccountService.grantInitialHearts(userId, profileId);
        return userId;
    }

    private Long newUserId() {
        return userRepository.save(User.create()).getId();
    }

    private int ledgerRows(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM heart_ledger_entry WHERE user_id = ?", Integer.class, userId);
    }

    private int amountOf(HeartTransactionType type, Long sourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT amount FROM heart_ledger_entry WHERE type = ? AND source_id = ?",
                Integer.class, type.name(), sourceId);
    }

    /** The wallet is the balance authority; the ledger must still add up to it. */
    private void assertReconciled(Long userId) {
        Integer summed = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM heart_ledger_entry WHERE user_id = ?",
                Integer.class, userId);
        assertEquals(summed, heartAccountService.balanceOf(userId),
                "wallet balance must equal the sum of its ledger");
    }
}

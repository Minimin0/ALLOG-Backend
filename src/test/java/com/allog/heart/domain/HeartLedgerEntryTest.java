package com.allog.heart.domain;

import com.allog.user.domain.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartLedgerEntryTest {

    @Test
    void theTypeDecidesTheSignSoASpendCannotBeRecordedAsACredit() {
        User user = User.create();

        assertEquals(3, HeartLedgerEntry
                .record(user, HeartTransactionType.INITIAL_GRANT, 3, 1L).getAmount());
        assertEquals(-1, HeartLedgerEntry
                .record(user, HeartTransactionType.GROUP_JOIN_SPEND, 1, 1L).getAmount());
        assertEquals(1, HeartLedgerEntry
                .record(user, HeartTransactionType.GROUP_JOIN_REFUND, 1, 1L).getAmount());
    }

    @Test
    void rejectsNonPositiveMagnitudes() {
        User user = User.create();

        assertThrows(IllegalArgumentException.class,
                () -> HeartLedgerEntry.record(user, HeartTransactionType.INITIAL_GRANT, 0, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> HeartLedgerEntry.record(user, HeartTransactionType.GROUP_JOIN_SPEND, -1, 1L));
    }

    @Test
    void requiresUserTypeAndSource() {
        User user = User.create();

        assertThrows(NullPointerException.class,
                () -> HeartLedgerEntry.record(null, HeartTransactionType.INITIAL_GRANT, 3, 1L));
        assertThrows(NullPointerException.class,
                () -> HeartLedgerEntry.record(user, null, 3, 1L));
        assertThrows(NullPointerException.class,
                () -> HeartLedgerEntry.record(user, HeartTransactionType.INITIAL_GRANT, 3, null));
    }

    @Test
    void creditDirectionMatchesTheDatabaseCheck() {
        assertTrue(HeartTransactionType.INITIAL_GRANT.isCredit());
        assertTrue(HeartTransactionType.GROUP_JOIN_REFUND.isCredit());
        assertEquals(false, HeartTransactionType.GROUP_JOIN_SPEND.isCredit());
    }

    @Test
    void aWalletNeverOpensWithADebt() {
        assertThrows(IllegalArgumentException.class, () -> HeartWallet.openWith(User.create(), -1));
        assertEquals(3, HeartWallet.openWith(User.create(), 3).getBalance());
    }
}

package com.allog.heart;

import com.allog.heart.domain.HeartTransactionType;
import com.allog.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HeartPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesFlywayV16BeforeJpaValidation() {
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16' AND success = TRUE",
                Integer.class));
    }

    @Test
    void migrationSetIsExactlyV1ThroughV16() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL"
                        + " ORDER BY CAST(version AS INT)", String.class);

        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9",
                        "10", "11", "12", "13", "14", "15", "16"),
                versions);
    }

    @Test
    void oneWalletPerUserIsEnforcedByTheDatabase() {
        Long userId = newUserId();
        insertWallet(userId, 3);

        assertThrows(DataAccessException.class, () -> insertWallet(userId, 5));
    }

    @Test
    void aWalletCannotGoNegative() {
        Long userId = newUserId();

        assertThrows(DataAccessException.class, () -> insertWallet(userId, -1));

        insertWallet(userId, 1);
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE heart_wallet SET balance = balance - 2 WHERE user_id = ?", userId));
    }

    @Test
    void walletRejectsAnUnknownUser() {
        assertThrows(DataAccessException.class, () -> insertWallet(-1L, 3));
    }

    @Test
    void ledgerRejectsUnknownTypesZeroAmountsAndUnknownUsers() {
        Long userId = newUserId();

        assertThrows(DataAccessException.class, () -> insertEntry(userId, "LOTTERY_WIN", 3, 1L));
        assertThrows(DataAccessException.class, () -> insertEntry(userId, "INITIAL_GRANT", 0, 2L));
        assertThrows(DataAccessException.class, () -> insertEntry(-1L, "INITIAL_GRANT", 3, 3L));
    }

    /** The direction CHECK is what stops a spend being written as a credit. */
    @Test
    void ledgerRejectsAmountsPointingTheWrongWay() {
        Long userId = newUserId();

        assertThrows(DataAccessException.class, () -> insertEntry(userId, "INITIAL_GRANT", -3, 11L));
        assertThrows(DataAccessException.class, () -> insertEntry(userId, "GROUP_JOIN_SPEND", 1, 12L));
        assertThrows(DataAccessException.class, () -> insertEntry(userId, "GROUP_JOIN_REFUND", -1, 13L));
    }

    @Test
    void ledgerAcceptsEachTypeInItsOwnDirection() {
        Long userId = newUserId();

        insertEntry(userId, "INITIAL_GRANT", 3, 21L);
        insertEntry(userId, "GROUP_JOIN_SPEND", -1, 22L);
        insertEntry(userId, "GROUP_JOIN_REFUND", 1, 22L);

        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM heart_ledger_entry WHERE user_id = ?", Integer.class, userId));
    }

    @Test
    void theSameOperationCannotBeRecordedTwice() {
        Long userId = newUserId();
        insertEntry(userId, "GROUP_JOIN_SPEND", -1, 31L);

        assertThrows(DataAccessException.class, () -> insertEntry(userId, "GROUP_JOIN_SPEND", -1, 31L));
    }

    /**
     * The type list is hand-written SQL. If a Java constant is added without extending the CHECK,
     * this fails here instead of at the first real transaction.
     */
    @Test
    void everyJavaTransactionTypeIsAcceptedByItsDatabaseCheck() {
        Long userId = newUserId();
        long source = 100L;

        for (HeartTransactionType type : HeartTransactionType.values()) {
            insertEntry(userId, type.name(), type.isCredit() ? 1 : -1, source++);
        }

        assertEquals(HeartTransactionType.values().length, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM heart_ledger_entry WHERE user_id = ? AND source_id >= 100",
                Integer.class, userId));
    }

    @Test
    void removingAWalletLeavesTheIdentityAndItsHistoryIntact() {
        Long userId = newUserId();
        insertWallet(userId, 3);
        insertEntry(userId, "INITIAL_GRANT", 3, 41L);

        jdbcTemplate.update("DELETE FROM heart_wallet WHERE user_id = ?", userId);

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM heart_ledger_entry WHERE user_id = ?", Integer.class, userId));
    }

    private Long newUserId() {
        User user = User.create();
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private void insertWallet(Long userId, int balance) {
        jdbcTemplate.update(
                "INSERT INTO heart_wallet (user_id, balance, created_at, updated_at)"
                        + " VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                userId, balance);
    }

    private void insertEntry(Long userId, String type, int amount, Long sourceId) {
        jdbcTemplate.update(
                "INSERT INTO heart_ledger_entry (user_id, type, amount, source_id, created_at)"
                        + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                userId, type, amount, sourceId);
    }
}

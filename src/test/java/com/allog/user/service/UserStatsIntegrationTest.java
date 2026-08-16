package com.allog.user.service;

import com.allog.heart.domain.HeartTransactionType;
import com.allog.heart.repository.HeartLedgerEntryRepository;
import com.allog.heart.service.HeartAccountService;
import com.allog.heart.service.HeartWalletNotFoundException;
import com.allog.user.domain.User;
import com.allog.user.dto.CreateUserOnboardingRequest;
import com.allog.user.dto.CreateUserProfileRequest;
import com.allog.user.dto.UserStatsResponse;
import com.allog.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profile creation and the joining grant are one transaction, so these are exercised together rather
 * than as separate units.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserStatsIntegrationTest {

    private static final int GRANT = HeartAccountService.INITIAL_GRANT_AMOUNT;

    @Autowired
    private UserProfileService profileService;

    @Autowired
    private UserStatsService statsService;

    @Autowired
    private HeartLedgerEntryRepository ledgerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clearProfileAndHeartTables() {
        jdbcTemplate.update("DELETE FROM heart_ledger_entry");
        jdbcTemplate.update("DELETE FROM heart_wallet");
        jdbcTemplate.update("DELETE FROM user_onboarding_interest");
        jdbcTemplate.update("DELETE FROM user_onboarding");
        jdbcTemplate.update("DELETE FROM user_profile");
    }

    @Test
    void finishingOnboardingOpensAWalletWithTheJoiningGrant() {
        Long userId = newUserId();

        profileService.create(userId, request());

        Long profileId = profileId(userId);
        assertEquals(1, walletRows(userId));
        assertEquals(GRANT, balance(userId));
        assertTrue(ledgerRepository.findByTypeAndSourceId(
                HeartTransactionType.INITIAL_GRANT, profileId).isPresent(),
                "the grant must be keyed to the profile that earned it");
        assertEquals(GRANT, statsService.read(userId).hearts());
    }

    @Test
    void aSecondProfileAttemptGrantsNothingExtra() {
        Long userId = newUserId();
        profileService.create(userId, request());

        assertThrows(ProfileAlreadyExistsException.class, () -> profileService.create(userId, request()));

        assertEquals(1, walletRows(userId));
        assertEquals(GRANT, balance(userId));
        assertEquals(1, ledgerRows(userId));
    }

    /**
     * The grant is made to fail by occupying the wallet's unique key first, so the whole profile
     * transaction has to roll back. Nothing test-only exists in production code; a real constraint
     * does the work.
     */
    @Test
    void aFailedGrantRollsBackTheEntireProfileCreation() {
        Long userId = newUserId();
        jdbcTemplate.update(
                "INSERT INTO heart_wallet (user_id, balance, created_at, updated_at)"
                        + " VALUES (?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                userId);

        assertThrows(RuntimeException.class, () -> profileService.create(userId, request()));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_profile WHERE user_id = ?", Integer.class, userId),
                "no profile may survive a failed grant");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_onboarding WHERE user_id = ?", Integer.class, userId));
        assertEquals(1, walletRows(userId), "only the pre-existing wallet row remains");
        assertEquals(0, balance(userId), "the pre-existing wallet was not credited");
    }

    @Test
    void statsReportsHeartsAndRewardPointsTogether() {
        Long userId = newUserId();
        profileService.create(userId, request());

        UserStatsResponse stats = statsService.read(userId);

        assertEquals(GRANT, stats.hearts());
        assertEquals(0, stats.rewardPoints(), "a member with no approvals has earned nothing");
        assertEquals(0, stats.successfulRoutines());
    }

    @Test
    void statsRequiresAProfile() {
        assertThrows(ProfileNotFoundException.class, () -> statsService.read(newUserId()));
    }

    /** A profile with no wallet is a broken invariant, not a member with zero hearts. */
    @Test
    void aMissingWalletBehindAProfileIsNotReportedAsZero() {
        Long userId = newUserId();
        profileService.create(userId, request());
        jdbcTemplate.update("DELETE FROM heart_wallet WHERE user_id = ?", userId);

        assertThrows(HeartWalletNotFoundException.class, () -> statsService.read(userId));
    }

    @Test
    void walletBalanceAlwaysEqualsItsLedger() {
        Long userId = newUserId();
        profileService.create(userId, request());

        assertEquals(
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(amount), 0) FROM heart_ledger_entry WHERE user_id = ?",
                        Integer.class, userId),
                balance(userId));
    }

    private Long newUserId() {
        return userRepository.save(User.create()).getId();
    }

    private Long profileId(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user_profile WHERE user_id = ?", Long.class, userId);
    }

    private int walletRows(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM heart_wallet WHERE user_id = ?", Integer.class, userId);
    }

    private int ledgerRows(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM heart_ledger_entry WHERE user_id = ?", Integer.class, userId);
    }

    private int balance(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM heart_wallet WHERE user_id = ?", Integer.class, userId);
    }

    private static CreateUserProfileRequest request() {
        CreateUserOnboardingRequest onboarding = new CreateUserOnboardingRequest();
        onboarding.setInterestRoutines(List.of("meal"));
        onboarding.setCoachStyle("supportive");
        onboarding.setAverageSleepHours(new BigDecimal("7.0"));
        onboarding.setExerciseDaysPerWeek(3);
        onboarding.setMealsPerDay(3);
        onboarding.setPreferredGroupDurationDays(7);

        CreateUserProfileRequest request = new CreateUserProfileRequest();
        request.setNickname("민지");
        request.setBirthDate(LocalDate.of(2000, 7, 30));
        request.setOnboarding(onboarding);
        return request;
    }
}

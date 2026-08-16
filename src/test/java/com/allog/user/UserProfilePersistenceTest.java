package com.allog.user;

import com.allog.user.domain.CoachStyle;
import com.allog.user.domain.Gender;
import com.allog.user.domain.InterestCategory;
import com.allog.user.domain.User;
import com.allog.user.domain.UserOnboarding;
import com.allog.user.domain.UserProfile;
import com.allog.user.repository.UserOnboardingRepository;
import com.allog.user.repository.UserProfileRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserProfilePersistenceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 16);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserProfileRepository profileRepository;

    @Autowired
    private UserOnboardingRepository onboardingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesFlywayV13AndV14BeforeJpaValidation() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('13', '14') AND success = TRUE",
                Integer.class
        );

        assertEquals(2, applied);
    }

    @Test
    void persistsProfileAndOnboardingAndReadsThemBackByUserId() {
        Long userId = newUserId();
        profileRepository.save(UserProfile.create(
                entityManager.getReference(User.class, userId), "민지", Gender.FEMALE, TODAY.minusYears(25), TODAY));
        onboardingRepository.saveAndFlush(UserOnboarding.create(
                entityManager.getReference(User.class, userId),
                EnumSet.of(InterestCategory.HYDRATION, InterestCategory.EXERCISE),
                CoachStyle.SUPPORTIVE, new BigDecimal("7.0"), 3, 3, 7));
        entityManager.clear();

        UserProfile profile = profileRepository.findByUser_Id(userId).orElseThrow();
        UserOnboarding onboarding = onboardingRepository.findByUser_Id(userId).orElseThrow();

        assertEquals("민지", profile.getNickname());
        assertEquals(Gender.FEMALE, profile.getGender());
        assertEquals(0, new BigDecimal("7.0").compareTo(onboarding.getAverageSleepHours()));
        assertEquals(
                Set.of(InterestCategory.HYDRATION, InterestCategory.EXERCISE),
                onboarding.getInterests());
    }

    @Test
    void oneProfilePerUserIsEnforcedByTheDatabase() {
        Long userId = newUserId();
        insertProfile(userId, "민지");

        assertThrows(DataAccessException.class, () -> insertProfile(userId, "다른이름"));
    }

    @Test
    void oneOnboardingPerUserIsEnforcedByTheDatabase() {
        Long userId = newUserId();
        insertOnboarding(userId, "SUPPORTIVE");

        assertThrows(DataAccessException.class, () -> insertOnboarding(userId, "HUMOROUS"));
    }

    @Test
    void profileRejectsUnknownUserAndUnknownGender() {
        assertThrows(DataAccessException.class, () -> insertProfile(-1L, "없는사용자"));

        Long userId = newUserId();
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO user_profile (user_id, nickname, gender, created_at, updated_at)"
                        + " VALUES (?, ?, 'OTHER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                userId, "민지"));
    }

    @Test
    void onboardingRejectsValuesOutsideTheAllowedRanges() {
        assertThrows(DataAccessException.class, () -> insertOnboardingWith(newUserId(), "WRONG", "7.0", 3, 3, 7));
        assertThrows(DataAccessException.class, () -> insertOnboardingWith(newUserId(), "SUPPORTIVE", "24.1", 3, 3, 7));
        assertThrows(DataAccessException.class, () -> insertOnboardingWith(newUserId(), "SUPPORTIVE", "-0.1", 3, 3, 7));
        assertThrows(DataAccessException.class, () -> insertOnboardingWith(newUserId(), "SUPPORTIVE", "7.0", 8, 3, 7));
        assertThrows(DataAccessException.class, () -> insertOnboardingWith(newUserId(), "SUPPORTIVE", "7.0", 3, 11, 7));
        assertThrows(DataAccessException.class, () -> insertOnboardingWith(newUserId(), "SUPPORTIVE", "7.0", 3, 3, 10));
    }

    @Test
    void interestRowsRejectUnknownValuesDuplicatesAndOrphans() {
        Long onboardingId = insertOnboarding(newUserId(), "SUPPORTIVE");
        insertInterest(onboardingId, "MEAL");

        assertThrows(DataAccessException.class, () -> insertInterest(onboardingId, "MEAL"));
        assertThrows(DataAccessException.class, () -> insertInterest(onboardingId, "DANCING"));
        assertThrows(DataAccessException.class, () -> insertInterest(-1L, "MEAL"));
    }

    /**
     * The database enum lists are hand-written SQL. If a Java constant is added without extending the
     * matching CHECK, this fails instead of the insert failing in production.
     */
    @Test
    void everyJavaEnumConstantIsAcceptedByItsDatabaseCheck() {
        for (Gender gender : Gender.values()) {
            Long userId = newUserId();
            jdbcTemplate.update(
                    "INSERT INTO user_profile (user_id, nickname, gender, created_at, updated_at)"
                            + " VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    userId, "닉", gender.name());
        }
        for (CoachStyle style : CoachStyle.values()) {
            insertOnboarding(newUserId(), style.name());
        }
        Long onboardingId = insertOnboarding(newUserId(), "SUPPORTIVE");
        for (InterestCategory interest : InterestCategory.values()) {
            insertInterest(onboardingId, interest.name());
        }

        assertEquals(
                InterestCategory.values().length,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_onboarding_interest WHERE onboarding_id = ?",
                        Integer.class, onboardingId));
    }

    @Test
    void deletingOnboardingRemovesItsInterestRows() {
        Long onboardingId = insertOnboarding(newUserId(), "SUPPORTIVE");
        insertInterest(onboardingId, "MEAL");

        jdbcTemplate.update("DELETE FROM user_onboarding WHERE id = ?", onboardingId);

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_onboarding_interest WHERE onboarding_id = ?",
                Integer.class, onboardingId));
    }

    @Test
    void identityRowSurvivesProfileRemovalSoHistoryStaysAttached() {
        Long userId = newUserId();
        insertProfile(userId, "민지");

        jdbcTemplate.update("DELETE FROM user_profile WHERE user_id = ?", userId);

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId));
    }

    @Test
    void deletingAUserThatStillHasAProfileIsRefused() {
        Long userId = newUserId();
        insertProfile(userId, "민지");

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId));
    }

    private Long newUserId() {
        User user = User.create();
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private void insertProfile(Long userId, String nickname) {
        jdbcTemplate.update(
                "INSERT INTO user_profile (user_id, nickname, created_at, updated_at)"
                        + " VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                userId, nickname);
    }

    private Long insertOnboarding(Long userId, String coachStyle) {
        insertOnboardingWith(userId, coachStyle, "7.0", 3, 3, 7);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user_onboarding WHERE user_id = ?", Long.class, userId);
    }

    private void insertOnboardingWith(
            Long userId, String coachStyle, String sleep, int exercise, int meals, int duration) {
        jdbcTemplate.update(
                "INSERT INTO user_onboarding (user_id, coach_style, average_sleep_hours,"
                        + " exercise_days_per_week, meals_per_day, preferred_group_duration_days,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                userId, coachStyle, new BigDecimal(sleep), exercise, meals, duration);
    }

    private void insertInterest(Long onboardingId, String interest) {
        jdbcTemplate.update(
                "INSERT INTO user_onboarding_interest (onboarding_id, interest) VALUES (?, ?)",
                onboardingId, interest);
    }

    @Test
    void migrationSetIsExactlyV1ThroughV15() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL"
                        + " ORDER BY CAST(version AS INT)", String.class);

        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"),
                versions);
    }
}

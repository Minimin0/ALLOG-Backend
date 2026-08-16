package com.allog.user.service;

import com.allog.user.domain.CoachStyle;
import com.allog.user.domain.Gender;
import com.allog.user.domain.InterestCategory;
import com.allog.user.domain.User;
import com.allog.user.dto.CreateUserOnboardingRequest;
import com.allog.user.dto.CreateUserProfileRequest;
import com.allog.user.dto.PatchUserProfileRequest;
import com.allog.user.dto.UserProfileResponse;
import com.allog.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not {@code @Transactional}: creation commits in its own transaction and the concurrency case needs
 * two threads to see each other's writes, so rows are cleaned up by hand instead.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserProfileIntegrationTest {

    @Autowired
    private UserProfileService profileService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // Also after: these tests commit, and a leftover profile row would block the
    // "delete from users" cleanup other suites rely on.
    @BeforeEach
    @AfterEach
    void clearProfileTables() {
        jdbcTemplate.update("DELETE FROM user_onboarding_interest");
        jdbcTemplate.update("DELETE FROM user_onboarding");
        jdbcTemplate.update("DELETE FROM user_profile");
    }

    @Test
    void createsProfileOnboardingAndInterestsTogether() {
        Long userId = newUserId();

        UserProfileResponse created = profileService.create(userId, request("민지"));

        assertEquals(userId, created.userId());
        assertEquals("민지", created.nickname());
        assertEquals("female", created.gender());
        assertEquals(List.of("hydration", "exercise"), created.onboarding().interestRoutines());
        assertEquals("supportive", created.onboarding().coachStyle());
        assertEquals(1, countProfiles(userId));
        assertEquals(1, countOnboarding(userId));
        assertEquals(2, countInterests());
    }

    @Test
    void readsBackWhatWasCreated() {
        Long userId = newUserId();
        profileService.create(userId, request("민지"));

        UserProfileResponse read = profileService.read(userId);

        assertEquals("민지", read.nickname());
        assertEquals(7, read.onboarding().preferredGroupDurationDays());
        assertEquals(0, new BigDecimal("7.0").compareTo(read.onboarding().averageSleepHours()));
    }

    @Test
    void readingBeforeOnboardingFails() {
        assertThrows(ProfileNotFoundException.class, () -> profileService.read(newUserId()));
    }

    @Test
    void creatingTwiceIsRefused() {
        Long userId = newUserId();
        profileService.create(userId, request("민지"));

        assertThrows(ProfileAlreadyExistsException.class, () -> profileService.create(userId, request("다시")));
        assertEquals(1, countProfiles(userId));
    }

    /**
     * The onboarding insert is made to fail by occupying its unique key first. Nothing test-only is
     * added to production code; a real constraint does the work.
     */
    @Test
    void onboardingFailureRollsBackTheProfileToo() {
        Long userId = newUserId();
        jdbcTemplate.update(
                "INSERT INTO user_onboarding (user_id, coach_style, average_sleep_hours,"
                        + " exercise_days_per_week, meals_per_day, preferred_group_duration_days,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'SUPPORTIVE', 7.0, 3, 3, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                userId);

        assertThrows(RuntimeException.class, () -> profileService.create(userId, request("민지")));

        assertEquals(0, countProfiles(userId), "profile must not survive a failed onboarding insert");
        assertEquals(1, countOnboarding(userId), "only the pre-existing onboarding row remains");
    }

    @Test
    void concurrentCreatesLeaveExactlyOneProfileAndOneConflict() throws Exception {
        Long userId = newUserId();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> attempt = () -> {
                barrier.await();
                try {
                    return profileService.create(userId, request("민지"));
                } catch (RuntimeException failure) {
                    return failure;
                }
            };
            Future<Object> first = executor.submit(attempt);
            Future<Object> second = executor.submit(attempt);

            List<Object> outcomes = List.of(first.get(), second.get());
            long created = outcomes.stream().filter(UserProfileResponse.class::isInstance).count();
            long refused = outcomes.stream().filter(ProfileAlreadyExistsException.class::isInstance).count();

            assertEquals(1, created, "exactly one writer may create the profile");
            assertEquals(1, refused, () -> "the loser must get a conflict, not: " + outcomes);
            outcomes.stream()
                    .filter(RuntimeException.class::isInstance)
                    .forEach(outcome -> assertInstanceOf(ProfileAlreadyExistsException.class, outcome));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, countProfiles(userId));
        assertEquals(1, countOnboarding(userId));
        assertEquals(2, countInterests());
    }

    @Test
    void patchChangesOnlyThePresentFields() throws Exception {
        Long userId = newUserId();
        profileService.create(userId, request("민지"));

        UserProfileResponse patched = profileService.patch(userId, patch("""
                {"nickname": "수정"}
                """));

        assertEquals("수정", patched.nickname());
        assertEquals("female", patched.gender(), "an absent field must be left alone");
        assertEquals(LocalDate.of(2000, 7, 30), patched.birthDate());
        assertEquals("supportive", patched.onboarding().coachStyle());
    }

    @Test
    void explicitNullClearsOptionalProfileFields() throws Exception {
        Long userId = newUserId();
        profileService.create(userId, request("민지"));

        profileService.patch(userId, patch("""
                {"gender": null, "birthDate": null}
                """));

        UserProfileResponse read = profileService.read(userId);
        assertNull(read.gender());
        assertNull(read.birthDate());
    }

    @Test
    void nestedPatchReplacesOneOnboardingFieldAndItsInterestSet() throws Exception {
        Long userId = newUserId();
        profileService.create(userId, request("민지"));

        profileService.patch(userId, patch("""
                {"onboarding": {"preferredGroupDurationDays": 14, "interestRoutines": ["sleep"]}}
                """));

        UserProfileResponse read = profileService.read(userId);
        assertEquals(14, read.onboarding().preferredGroupDurationDays());
        assertEquals(List.of("sleep"), read.onboarding().interestRoutines());
        assertEquals("supportive", read.onboarding().coachStyle(), "an absent nested field is untouched");
        assertEquals(1, countInterests(), "the replaced set must not leave the old rows behind");
    }

    @Test
    void clearingAMandatoryFieldIsRefusedAndChangesNothing() throws Exception {
        Long userId = newUserId();
        profileService.create(userId, request("민지"));

        assertThrows(RuntimeException.class, () -> profileService.patch(userId, patch("""
                {"nickname": null}
                """)));

        assertEquals("민지", profileService.read(userId).nickname());
    }

    @Test
    void patchingBeforeOnboardingFails() throws Exception {
        assertThrows(ProfileNotFoundException.class,
                () -> profileService.patch(newUserId(), patch("{\"nickname\": \"민지\"}")));
    }

    @Test
    void aRejectedPatchLeavesTheStoredValuesUntouched() throws Exception {
        Long userId = newUserId();
        profileService.create(userId, request("민지"));

        assertThrows(RuntimeException.class, () -> profileService.patch(userId, patch("""
                {"onboarding": {"exerciseDaysPerWeek": 99}}
                """)));

        assertEquals(3, profileService.read(userId).onboarding().exerciseDaysPerWeek());
    }

    @Test
    void storedPersonalDataIsLimitedToTheAgreedColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns"
                        + " WHERE LOWER(table_name) = 'user_profile'", String.class);

        assertTrue(columns.containsAll(List.of("nickname", "gender", "birth_date")));
        assertTrue(columns.stream().noneMatch(
                name -> name.contains("email") || name.contains("height")
                        || name.contains("weight") || name.contains("image")),
                () -> "profile must not store deferred personal data, found: " + columns);
    }

    private Long newUserId() {
        return userRepository.save(User.create()).getId();
    }

    private PatchUserProfileRequest patch(String json) {
        return objectMapper.readValue(json, PatchUserProfileRequest.class);
    }

    private static CreateUserProfileRequest request(String nickname) {
        CreateUserOnboardingRequest onboarding = new CreateUserOnboardingRequest();
        onboarding.setInterestRoutines(List.of("hydration", "exercise"));
        onboarding.setCoachStyle("supportive");
        onboarding.setAverageSleepHours(new BigDecimal("7.0"));
        onboarding.setExerciseDaysPerWeek(3);
        onboarding.setMealsPerDay(3);
        onboarding.setPreferredGroupDurationDays(7);

        CreateUserProfileRequest request = new CreateUserProfileRequest();
        request.setNickname(nickname);
        request.setGender("female");
        request.setBirthDate(LocalDate.of(2000, 7, 30));
        request.setOnboarding(onboarding);
        return request;
    }

    private int countProfiles(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_profile WHERE user_id = ?", Integer.class, userId);
    }

    private int countOnboarding(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_onboarding WHERE user_id = ?", Integer.class, userId);
    }

    private int countInterests() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_onboarding_interest", Integer.class);
    }

    /** Guards the enum values the recommendation taxonomy is expected to keep offering. */
    @Test
    void interestTaxonomyAndCoachStylesAreTheAgreedSets() {
        assertEquals(
                List.of("HYDRATION", "EXERCISE", "MEAL", "SLEEP", "SKINCARE"),
                java.util.Arrays.stream(InterestCategory.values()).map(Enum::name).toList());
        assertEquals(
                List.of("SUPPORTIVE", "PRESSURING", "FACT_BASED", "HUMOROUS"),
                java.util.Arrays.stream(CoachStyle.values()).map(Enum::name).toList());
        assertEquals(List.of("FEMALE", "MALE"),
                java.util.Arrays.stream(Gender.values()).map(Enum::name).toList());
    }
}

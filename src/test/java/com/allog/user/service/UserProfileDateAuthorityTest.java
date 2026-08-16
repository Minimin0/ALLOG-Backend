package com.allog.user.service;

import com.allog.user.domain.User;
import com.allog.user.domain.UserProfileValidationException;
import com.allog.user.dto.CreateUserOnboardingRequest;
import com.allog.user.dto.CreateUserProfileRequest;
import com.allog.user.dto.PatchUserProfileRequest;
import com.allog.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ALLOG's calendar is Asia/Seoul, so "not in the future" has to be judged against the day the member
 * is living in. The clock is pinned to an instant where UTC and KST disagree about the date: at
 * 2026-08-16T15:30Z it is already 2026-08-17 in Seoul. A UTC implementation calls that date the
 * future and rejects it, so these tests fail if the fix is ever reverted.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(UserProfileDateAuthorityTest.FixedClockConfig.class)
class UserProfileDateAuthorityTest {

    private static final Instant PINNED = Instant.parse("2026-08-16T15:30:00Z");
    private static final LocalDate TODAY_IN_SEOUL = LocalDate.of(2026, 8, 17);
    private static final LocalDate TODAY_IN_UTC = LocalDate.of(2026, 8, 16);

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
    void clearProfiles() {
        jdbcTemplate.update("DELETE FROM user_onboarding_interest");
        jdbcTemplate.update("DELETE FROM user_onboarding");
        jdbcTemplate.update("DELETE FROM user_profile");
    }

    @Test
    void theTwoZonesReallyDisagreeAtThisInstant() {
        assertEquals(TODAY_IN_SEOUL, LocalDate.ofInstant(PINNED, java.time.ZoneId.of("Asia/Seoul")));
        assertEquals(TODAY_IN_UTC, LocalDate.ofInstant(PINNED, ZoneOffset.UTC));
    }

    @Test
    void createAcceptsTodayInKoreaEvenWhileUtcIsStillYesterday() {
        Long userId = newUserId();

        var created = profileService.create(userId, request(TODAY_IN_SEOUL));

        assertEquals(TODAY_IN_SEOUL, created.birthDate());
    }

    @Test
    void createRejectsTomorrowInKorea() {
        Long userId = newUserId();

        assertThrows(UserProfileValidationException.class,
                () -> profileService.create(userId, request(TODAY_IN_SEOUL.plusDays(1))));
    }

    @Test
    void patchAcceptsTodayInKoreaToo() throws Exception {
        Long userId = newUserId();
        profileService.create(userId, request(LocalDate.of(2000, 1, 1)));

        profileService.patch(userId, patch("{\"birthDate\": \"" + TODAY_IN_SEOUL + "\"}"));

        assertEquals(TODAY_IN_SEOUL, profileService.read(userId).birthDate());
    }

    @Test
    void patchRejectsTomorrowInKorea() throws Exception {
        Long userId = newUserId();
        profileService.create(userId, request(LocalDate.of(2000, 1, 1)));

        assertThrows(UserProfileValidationException.class,
                () -> profileService.patch(userId,
                        patch("{\"birthDate\": \"" + TODAY_IN_SEOUL.plusDays(1) + "\"}")));

        assertEquals(LocalDate.of(2000, 1, 1), profileService.read(userId).birthDate());
    }

    /** Both write paths must agree on what day it is, not just each be self-consistent. */
    @Test
    void createAndPatchShareTheSameCalendarAuthority() throws Exception {
        Long createUser = newUserId();
        Long patchUser = newUserId();
        profileService.create(patchUser, request(LocalDate.of(2000, 1, 1)));

        profileService.create(createUser, request(TODAY_IN_SEOUL));
        profileService.patch(patchUser, patch("{\"birthDate\": \"" + TODAY_IN_SEOUL + "\"}"));

        assertEquals(TODAY_IN_SEOUL, profileService.read(createUser).birthDate());
        assertEquals(TODAY_IN_SEOUL, profileService.read(patchUser).birthDate());
    }

    private Long newUserId() {
        return userRepository.save(User.create()).getId();
    }

    private PatchUserProfileRequest patch(String json) {
        return objectMapper.readValue(json, PatchUserProfileRequest.class);
    }

    private static CreateUserProfileRequest request(LocalDate birthDate) {
        CreateUserOnboardingRequest onboarding = new CreateUserOnboardingRequest();
        onboarding.setInterestRoutines(List.of("meal"));
        onboarding.setCoachStyle("supportive");
        onboarding.setAverageSleepHours(new BigDecimal("7.0"));
        onboarding.setExerciseDaysPerWeek(3);
        onboarding.setMealsPerDay(3);
        onboarding.setPreferredGroupDurationDays(7);

        CreateUserProfileRequest request = new CreateUserProfileRequest();
        request.setNickname("민지");
        request.setBirthDate(birthDate);
        request.setOnboarding(onboarding);
        return request;
    }

    /** A real fixed Clock: withZone already yields the same instant in another zone. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock profileDateTestClock() {
            return Clock.fixed(PINNED, ZoneOffset.UTC);
        }
    }
}

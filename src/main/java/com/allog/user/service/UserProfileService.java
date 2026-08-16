package com.allog.user.service;

import com.allog.user.domain.UserOnboarding;
import com.allog.user.domain.UserProfile;
import com.allog.user.dto.CreateUserProfileRequest;
import com.allog.user.dto.InvalidFieldException;
import com.allog.user.dto.PatchUserOnboardingRequest;
import com.allog.user.dto.PatchUserProfileRequest;
import com.allog.user.dto.UserProfileResponse;
import com.allog.user.repository.UserOnboardingRepository;
import com.allog.user.repository.UserProfileRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

@Service
public class UserProfileService {

    /**
     * ALLOG's calendar authority. A birth date is "in the future" against the day the member is
     * living in, not against UTC - between 00:00 and 09:00 KST those are different dates.
     */
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final UserProfileCreationService creationService;
    private final UserProfileRepository profileRepository;
    private final UserOnboardingRepository onboardingRepository;
    private final Clock clock;

    public UserProfileService(
            UserProfileCreationService creationService,
            UserProfileRepository profileRepository,
            UserOnboardingRepository onboardingRepository,
            Clock clock
    ) {
        this.creationService = Objects.requireNonNull(creationService);
        this.profileRepository = Objects.requireNonNull(profileRepository);
        this.onboardingRepository = Objects.requireNonNull(onboardingRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse read(Long userId) {
        UserProfile profile = profileRepository.findByUser_Id(userId)
                .orElseThrow(ProfileNotFoundException::new);
        return UserProfileResponse.from(userId, profile, requireOnboarding(userId));
    }

    /**
     * Deliberately not {@code @Transactional}: the create runs in its own transaction so a unique-key
     * rejection does not doom this one, and the recheck afterwards can still read the database.
     *
     * <p>The pre-check answers the ordinary repeat call cheaply; the unique key is what actually
     * decides a race. Only a violation that left a profile behind becomes 409 - anything else is a
     * different fault and is rethrown rather than disguised.
     */
    public UserProfileResponse create(Long userId, CreateUserProfileRequest request) {
        if (profileRepository.existsByUser_Id(userId)) {
            throw new ProfileAlreadyExistsException();
        }
        try {
            return creationService.create(userId, request, today());
        } catch (DataIntegrityViolationException violation) {
            if (profileRepository.existsByUser_Id(userId)) {
                throw new ProfileAlreadyExistsException();
            }
            throw violation;
        }
    }

    @Transactional
    public UserProfileResponse patch(Long userId, PatchUserProfileRequest request) {
        UserProfile profile = profileRepository.findByUser_Id(userId)
                .orElseThrow(ProfileNotFoundException::new);
        UserOnboarding onboarding = requireOnboarding(userId);

        if (request.isNicknamePresent()) {
            profile.updateNickname(requirePresentValue(request.getNickname(), "nickname"));
        }
        if (request.isGenderPresent()) {
            profile.updateGender(request.getGender());
        }
        if (request.isBirthDatePresent()) {
            profile.updateBirthDate(request.getBirthDate(), today());
        }
        if (request.isOnboardingPresent()) {
            patchOnboarding(onboarding, requirePresentValue(request.getOnboarding(), "onboarding"));
        }
        return UserProfileResponse.from(userId, profile, onboarding);
    }

    private void patchOnboarding(UserOnboarding onboarding, PatchUserOnboardingRequest request) {
        if (request.isInterestRoutinesPresent()) {
            onboarding.updateInterests(request.getInterests());
        }
        if (request.isCoachStylePresent()) {
            onboarding.updateCoachStyle(
                    requirePresentValue(request.getCoachStyle(), "onboarding.coachStyle"));
        }
        if (request.isAverageSleepHoursPresent()) {
            onboarding.updateAverageSleepHours(
                    requirePresentValue(request.getAverageSleepHours(), "onboarding.averageSleepHours"));
        }
        if (request.isExerciseDaysPerWeekPresent()) {
            onboarding.updateExerciseDaysPerWeek(
                    requirePresentValue(request.getExerciseDaysPerWeek(), "onboarding.exerciseDaysPerWeek"));
        }
        if (request.isMealsPerDayPresent()) {
            onboarding.updateMealsPerDay(
                    requirePresentValue(request.getMealsPerDay(), "onboarding.mealsPerDay"));
        }
        if (request.isPreferredGroupDurationDaysPresent()) {
            onboarding.updatePreferredGroupDurationDays(
                    requirePresentValue(
                            request.getPreferredGroupDurationDays(), "onboarding.preferredGroupDurationDays"));
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SERVICE_ZONE));
    }

    /** These fields can be replaced but not cleared, so an explicit null is a bad request. */
    private static <T> T requirePresentValue(T value, String fieldName) {
        if (value == null) {
            throw new InvalidFieldException(fieldName, "must not be null");
        }
        return value;
    }

    /**
     * Onboarding is written with the profile in one transaction, so its absence here is a broken
     * invariant rather than a client mistake.
     */
    private UserOnboarding requireOnboarding(Long userId) {
        return onboardingRepository.findByUser_Id(userId)
                .orElseThrow(() -> new IllegalStateException("profile exists without onboarding"));
    }
}

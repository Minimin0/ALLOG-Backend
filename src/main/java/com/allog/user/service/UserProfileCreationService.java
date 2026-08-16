package com.allog.user.service;

import com.allog.user.domain.User;
import com.allog.user.domain.UserOnboarding;
import com.allog.user.domain.UserProfile;
import com.allog.user.dto.CreateUserProfileRequest;
import com.allog.user.dto.UserProfileResponse;
import com.allog.user.repository.UserOnboardingRepository;
import com.allog.user.repository.UserProfileRepository;
import com.allog.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Writes the profile and its onboarding in one transaction, so a member can never end up with a
 * profile and no onboarding - a state the read API has no sensible answer for.
 *
 * <p>The calendar date is supplied by the caller rather than read here, so one place decides what
 * "today" means.
 *
 * <p>Separate from {@link UserProfileService} and {@code REQUIRES_NEW} for the same reason
 * {@code UserIdentityCreationService} is: when the unique key rejects a second concurrent create,
 * only this inner transaction is marked rollback-only, leaving the caller able to query what
 * actually happened and answer 409 instead of 500.
 */
@Service
public class UserProfileCreationService {

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final UserOnboardingRepository onboardingRepository;

    public UserProfileCreationService(
            UserRepository userRepository,
            UserProfileRepository profileRepository,
            UserOnboardingRepository onboardingRepository
    ) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.profileRepository = Objects.requireNonNull(profileRepository);
        this.onboardingRepository = Objects.requireNonNull(onboardingRepository);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserProfileResponse create(Long userId, CreateUserProfileRequest request, LocalDate today) {
        // A reference, not a fetch: the principal already proves this user exists, and the FK is the
        // authority if it somehow does not.
        User user = userRepository.getReferenceById(userId);

        UserProfile profile = profileRepository.save(UserProfile.create(
                user,
                request.getNickname(),
                request.getGender(),
                request.getBirthDate(),
                today
        ));

        var onboardingRequest = request.getOnboarding();
        UserOnboarding onboarding = onboardingRepository.saveAndFlush(UserOnboarding.create(
                user,
                onboardingRequest.getInterests(),
                onboardingRequest.getCoachStyle(),
                onboardingRequest.getAverageSleepHours(),
                onboardingRequest.getExerciseDaysPerWeek(),
                onboardingRequest.getMealsPerDay(),
                onboardingRequest.getPreferredGroupDurationDays()
        ));

        return UserProfileResponse.from(userId, profile, onboarding);
    }
}

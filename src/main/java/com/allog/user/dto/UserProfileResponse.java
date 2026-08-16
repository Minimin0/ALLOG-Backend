package com.allog.user.dto;

import com.allog.user.domain.UserOnboarding;
import com.allog.user.domain.UserProfile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Built inside the service transaction, because {@code open-in-view} is disabled and the interest
 * collection is lazy.
 *
 * <p>Carries no email, height, weight, profile image or stats: those are deferred, and a placeholder
 * would be a value the client could not tell apart from a real one.
 */
public record UserProfileResponse(
        Long userId,
        String nickname,
        String gender,
        LocalDate birthDate,
        Onboarding onboarding
) {

    public static UserProfileResponse from(Long userId, UserProfile profile, UserOnboarding onboarding) {
        return new UserProfileResponse(
                userId,
                profile.getNickname(),
                WireEnum.toWire(profile.getGender()),
                profile.getBirthDate(),
                Onboarding.from(onboarding)
        );
    }

    public record Onboarding(
            List<String> interestRoutines,
            String coachStyle,
            BigDecimal averageSleepHours,
            int exerciseDaysPerWeek,
            int mealsPerDay,
            int preferredGroupDurationDays
    ) {

        static Onboarding from(UserOnboarding onboarding) {
            return new Onboarding(
                    // EnumSet iterates in declaration order, so the array is stable across reads.
                    onboarding.getInterests().stream().map(WireEnum::toWire).toList(),
                    WireEnum.toWire(onboarding.getCoachStyle()),
                    onboarding.getAverageSleepHours(),
                    onboarding.getExerciseDaysPerWeek(),
                    onboarding.getMealsPerDay(),
                    onboarding.getPreferredGroupDurationDays()
            );
        }
    }
}

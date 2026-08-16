package com.allog.user.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserOnboardingTest {

    private static UserOnboarding onboarding(Set<InterestCategory> interests) {
        return UserOnboarding.create(
                User.create(), interests, CoachStyle.SUPPORTIVE, new BigDecimal("7.0"), 3, 3, 7);
    }

    @Test
    void requiresAtLeastOneInterest() {
        assertThrows(IllegalArgumentException.class, () -> onboarding(EnumSet.noneOf(InterestCategory.class)));
        assertThrows(IllegalArgumentException.class, () -> onboarding(null));
    }

    @Test
    void collapsesDuplicateInterestsToASet() {
        Set<InterestCategory> withDuplicates = new HashSet<>(List.of(InterestCategory.MEAL, InterestCategory.MEAL));

        assertEquals(Set.of(InterestCategory.MEAL), onboarding(withDuplicates).getInterests());
    }

    @Test
    void copiesTheInterestSetDefensivelyAndReturnsAnUnmodifiableView() {
        Set<InterestCategory> caller = EnumSet.of(InterestCategory.MEAL);
        UserOnboarding created = onboarding(caller);

        caller.add(InterestCategory.SLEEP);

        assertEquals(Set.of(InterestCategory.MEAL), created.getInterests());
        assertThrows(UnsupportedOperationException.class,
                () -> created.getInterests().add(InterestCategory.SLEEP));
    }

    @Test
    void rejectsSleepHoursOutsideTheDayAndFinerThanOneDecimal() {
        assertThrows(IllegalArgumentException.class, () -> UserOnboarding.create(
                User.create(), EnumSet.of(InterestCategory.MEAL), CoachStyle.SUPPORTIVE,
                new BigDecimal("-0.1"), 3, 3, 7));
        assertThrows(IllegalArgumentException.class, () -> UserOnboarding.create(
                User.create(), EnumSet.of(InterestCategory.MEAL), CoachStyle.SUPPORTIVE,
                new BigDecimal("24.1"), 3, 3, 7));
        assertThrows(IllegalArgumentException.class, () -> UserOnboarding.create(
                User.create(), EnumSet.of(InterestCategory.MEAL), CoachStyle.SUPPORTIVE,
                new BigDecimal("7.05"), 3, 3, 7));
    }

    @Test
    void normalisesSleepHoursToOneDecimalAtTheBoundaries() {
        UserOnboarding zero = UserOnboarding.create(
                User.create(), EnumSet.of(InterestCategory.MEAL), CoachStyle.SUPPORTIVE,
                BigDecimal.ZERO, 0, 0, 7);
        UserOnboarding full = UserOnboarding.create(
                User.create(), EnumSet.of(InterestCategory.MEAL), CoachStyle.SUPPORTIVE,
                new BigDecimal("24"), 7, 10, 30);

        assertEquals(new BigDecimal("0.0"), zero.getAverageSleepHours());
        assertEquals(new BigDecimal("24.0"), full.getAverageSleepHours());
    }

    @Test
    void rejectsCountsOutsideTheirRange() {
        UserOnboarding created = onboarding(EnumSet.of(InterestCategory.MEAL));

        assertThrows(IllegalArgumentException.class, () -> created.updateExerciseDaysPerWeek(-1));
        assertThrows(IllegalArgumentException.class, () -> created.updateExerciseDaysPerWeek(8));
        assertThrows(IllegalArgumentException.class, () -> created.updateMealsPerDay(-1));
        assertThrows(IllegalArgumentException.class, () -> created.updateMealsPerDay(11));
    }

    @Test
    void acceptsOnlyTheOfferedGroupDurations() {
        UserOnboarding created = onboarding(EnumSet.of(InterestCategory.MEAL));

        created.updatePreferredGroupDurationDays(14);
        assertEquals(14, created.getPreferredGroupDurationDays());

        assertThrows(IllegalArgumentException.class, () -> created.updatePreferredGroupDurationDays(10));
        assertThrows(IllegalArgumentException.class, () -> created.updatePreferredGroupDurationDays(0));
    }

    @Test
    void rejectsClearingMandatoryOnboardingValues() {
        UserOnboarding created = onboarding(EnumSet.of(InterestCategory.MEAL));

        assertThrows(NullPointerException.class, () -> created.updateCoachStyle(null));
        assertThrows(NullPointerException.class, () -> created.updateAverageSleepHours(null));
        assertThrows(IllegalArgumentException.class,
                () -> created.updateInterests(EnumSet.noneOf(InterestCategory.class)));
    }
}

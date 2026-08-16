package com.allog.user.dto;

import com.allog.user.domain.CoachStyle;
import com.allog.user.domain.InterestCategory;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Wire values are converted to domain enums inside the setters, so a value outside the contract is
 * rejected while the body is still being read rather than deep inside a transaction.
 */
public class CreateUserOnboardingRequest {

    @NotEmpty
    private Set<InterestCategory> interests;

    @NotNull
    private CoachStyle coachStyle;

    @NotNull
    private BigDecimal averageSleepHours;

    @NotNull
    private Integer exerciseDaysPerWeek;

    @NotNull
    private Integer mealsPerDay;

    @NotNull
    private Integer preferredGroupDurationDays;

    @JsonAnySetter
    void rejectUnknown(String fieldName, Object ignoredValue) {
        throw new UnknownJsonFieldException("onboarding." + fieldName);
    }

    public void setInterestRoutines(List<String> interestRoutines) {
        this.interests = InterestWire.toDomain(interestRoutines);
    }

    public void setCoachStyle(String coachStyle) {
        this.coachStyle = WireEnum.fromWire(CoachStyle.class, coachStyle, "onboarding.coachStyle");
    }

    public void setAverageSleepHours(BigDecimal averageSleepHours) {
        this.averageSleepHours = averageSleepHours;
    }

    public void setExerciseDaysPerWeek(Integer exerciseDaysPerWeek) {
        this.exerciseDaysPerWeek = exerciseDaysPerWeek;
    }

    public void setMealsPerDay(Integer mealsPerDay) {
        this.mealsPerDay = mealsPerDay;
    }

    public void setPreferredGroupDurationDays(Integer preferredGroupDurationDays) {
        this.preferredGroupDurationDays = preferredGroupDurationDays;
    }

    public Set<InterestCategory> getInterests() {
        return interests;
    }

    public CoachStyle getCoachStyle() {
        return coachStyle;
    }

    public BigDecimal getAverageSleepHours() {
        return averageSleepHours;
    }

    public Integer getExerciseDaysPerWeek() {
        return exerciseDaysPerWeek;
    }

    public Integer getMealsPerDay() {
        return mealsPerDay;
    }

    public Integer getPreferredGroupDurationDays() {
        return preferredGroupDurationDays;
    }

    /** Shared by create and patch so both reject the same shapes for the same reasons. */
    static final class InterestWire {

        private InterestWire() {
        }

        static Set<InterestCategory> toDomain(List<String> wireValues) {
            if (wireValues == null || wireValues.isEmpty()) {
                throw new InvalidFieldException("onboarding.interestRoutines", "must contain at least one category");
            }
            Set<InterestCategory> interests = EnumSet.noneOf(InterestCategory.class);
            for (String wireValue : wireValues) {
                if (wireValue == null) {
                    throw new InvalidFieldException("onboarding.interestRoutines", "must not contain null");
                }
                InterestCategory category = WireEnum.fromWire(
                        InterestCategory.class, wireValue, "onboarding.interestRoutines");
                if (!interests.add(category)) {
                    throw new InvalidFieldException(
                            "onboarding.interestRoutines", "must not contain duplicate categories");
                }
            }
            return interests;
        }
    }
}

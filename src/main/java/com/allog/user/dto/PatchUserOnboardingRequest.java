package com.allog.user.dto;

import com.allog.user.domain.CoachStyle;
import com.allog.user.domain.InterestCategory;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Nested partial update. Onboarding is mandatory state once it exists, so every field here can be
 * left absent or replaced, but none of them can be cleared.
 */
public class PatchUserOnboardingRequest {

    private boolean interestRoutinesPresent;
    private Set<InterestCategory> interests;

    private boolean coachStylePresent;
    private CoachStyle coachStyle;

    private boolean averageSleepHoursPresent;
    private BigDecimal averageSleepHours;

    private boolean exerciseDaysPerWeekPresent;
    private Integer exerciseDaysPerWeek;

    private boolean mealsPerDayPresent;
    private Integer mealsPerDay;

    private boolean preferredGroupDurationDaysPresent;
    private Integer preferredGroupDurationDays;

    @JsonAnySetter
    void rejectUnknown(String fieldName, Object ignoredValue) {
        throw new UnknownJsonFieldException("onboarding." + fieldName);
    }

    @JsonSetter("interestRoutines")
    void setInterestRoutines(List<String> value) {
        this.interestRoutinesPresent = true;
        this.interests = CreateUserOnboardingRequest.InterestWire.toDomain(value);
    }

    @JsonSetter("coachStyle")
    void setCoachStyle(String value) {
        this.coachStylePresent = true;
        this.coachStyle = WireEnum.fromWire(CoachStyle.class, value, "onboarding.coachStyle");
    }

    @JsonSetter("averageSleepHours")
    void setAverageSleepHours(BigDecimal value) {
        this.averageSleepHoursPresent = true;
        this.averageSleepHours = value;
    }

    @JsonSetter("exerciseDaysPerWeek")
    void setExerciseDaysPerWeek(Integer value) {
        this.exerciseDaysPerWeekPresent = true;
        this.exerciseDaysPerWeek = value;
    }

    @JsonSetter("mealsPerDay")
    void setMealsPerDay(Integer value) {
        this.mealsPerDayPresent = true;
        this.mealsPerDay = value;
    }

    @JsonSetter("preferredGroupDurationDays")
    void setPreferredGroupDurationDays(Integer value) {
        this.preferredGroupDurationDaysPresent = true;
        this.preferredGroupDurationDays = value;
    }

    public boolean isInterestRoutinesPresent() {
        return interestRoutinesPresent;
    }

    public Set<InterestCategory> getInterests() {
        return interests;
    }

    public boolean isCoachStylePresent() {
        return coachStylePresent;
    }

    public CoachStyle getCoachStyle() {
        return coachStyle;
    }

    public boolean isAverageSleepHoursPresent() {
        return averageSleepHoursPresent;
    }

    public BigDecimal getAverageSleepHours() {
        return averageSleepHours;
    }

    public boolean isExerciseDaysPerWeekPresent() {
        return exerciseDaysPerWeekPresent;
    }

    public Integer getExerciseDaysPerWeek() {
        return exerciseDaysPerWeek;
    }

    public boolean isMealsPerDayPresent() {
        return mealsPerDayPresent;
    }

    public Integer getMealsPerDay() {
        return mealsPerDay;
    }

    public boolean isPreferredGroupDurationDaysPresent() {
        return preferredGroupDurationDaysPresent;
    }

    public Integer getPreferredGroupDurationDays() {
        return preferredGroupDurationDays;
    }
}

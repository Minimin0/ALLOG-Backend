package com.allog.user.domain;

import com.allog.common.persistence.BaseTimeEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The answers a member gave at onboarding, kept as the input a routine recommendation reads.
 *
 * <p>Separate from {@link UserProfile} because these are recommendation inputs that will change as
 * recommendation does, and a profile holding a nickname should not be migrated every time that
 * happens.
 *
 * <p>No {@code toString}: these are personal facts about sleep and eating.
 */
@Entity
@Table(
        name = "user_onboarding",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_onboarding_user",
                columnNames = "user_id"
        )
)
public class UserOnboarding extends BaseTimeEntity {

    private static final BigDecimal MIN_SLEEP_HOURS = BigDecimal.ZERO;
    private static final BigDecimal MAX_SLEEP_HOURS = BigDecimal.valueOf(24);
    private static final int SLEEP_HOURS_SCALE = 1;
    private static final int MIN_EXERCISE_DAYS = 0;
    private static final int MAX_EXERCISE_DAYS = 7;
    private static final int MIN_MEALS = 0;
    private static final int MAX_MEALS = 10;
    private static final Set<Integer> ALLOWED_DURATION_DAYS = Set.of(7, 14, 30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "coach_style", nullable = false, length = 32)
    private CoachStyle coachStyle;

    @Column(name = "average_sleep_hours", nullable = false, precision = 3, scale = 1)
    private BigDecimal averageSleepHours;

    // Narrow columns for values that cannot outgrow them; the Java type stays int so the domain
    // keeps arithmetic-friendly types and the JDBC type is stated rather than inferred.
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "exercise_days_per_week", nullable = false)
    private int exerciseDaysPerWeek;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "meals_per_day", nullable = false)
    private int mealsPerDay;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "preferred_group_duration_days", nullable = false)
    private int preferredGroupDurationDays;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "user_onboarding_interest",
            joinColumns = @JoinColumn(name = "onboarding_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "interest", nullable = false, length = 32)
    private Set<InterestCategory> interests = EnumSet.noneOf(InterestCategory.class);

    protected UserOnboarding() {
    }

    private UserOnboarding(
            User user,
            Set<InterestCategory> interests,
            CoachStyle coachStyle,
            BigDecimal averageSleepHours,
            int exerciseDaysPerWeek,
            int mealsPerDay,
            int preferredGroupDurationDays
    ) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.interests = requireInterests(interests);
        this.coachStyle = requireCoachStyle(coachStyle);
        this.averageSleepHours = requireSleepHours(averageSleepHours);
        this.exerciseDaysPerWeek = requireExerciseDays(exerciseDaysPerWeek);
        this.mealsPerDay = requireMeals(mealsPerDay);
        this.preferredGroupDurationDays = requireDurationDays(preferredGroupDurationDays);
    }

    public static UserOnboarding create(
            User user,
            Set<InterestCategory> interests,
            CoachStyle coachStyle,
            BigDecimal averageSleepHours,
            int exerciseDaysPerWeek,
            int mealsPerDay,
            int preferredGroupDurationDays
    ) {
        return new UserOnboarding(
                user,
                interests,
                coachStyle,
                averageSleepHours,
                exerciseDaysPerWeek,
                mealsPerDay,
                preferredGroupDurationDays
        );
    }

    public void updateInterests(Set<InterestCategory> value) {
        this.interests = requireInterests(value);
    }

    public void updateCoachStyle(CoachStyle value) {
        this.coachStyle = requireCoachStyle(value);
    }

    public void updateAverageSleepHours(BigDecimal value) {
        this.averageSleepHours = requireSleepHours(value);
    }

    public void updateExerciseDaysPerWeek(int value) {
        this.exerciseDaysPerWeek = requireExerciseDays(value);
    }

    public void updateMealsPerDay(int value) {
        this.mealsPerDay = requireMeals(value);
    }

    public void updatePreferredGroupDurationDays(int value) {
        this.preferredGroupDurationDays = requireDurationDays(value);
    }

    private static CoachStyle requireCoachStyle(CoachStyle value) {
        if (value == null) {
            throw new UserProfileValidationException("onboarding.coachStyle", "must not be null");
        }
        return value;
    }

    /** Defensive copy into an EnumSet: the caller's set stays theirs, and iteration order is stable. */
    private static Set<InterestCategory> requireInterests(Set<InterestCategory> value) {
        if (value == null || value.isEmpty()) {
            throw new UserProfileValidationException(
                    "onboarding.interestRoutines", "must contain at least one category");
        }
        if (value.contains(null)) {
            throw new UserProfileValidationException(
                    "onboarding.interestRoutines", "must not contain null");
        }
        return EnumSet.copyOf(value);
    }

    /**
     * One decimal place, matching DECIMAL(3,1). A finer value is rejected rather than rounded: silently
     * turning 7.05 into 7.1 would answer a question the member did not ask.
     */
    private static BigDecimal requireSleepHours(BigDecimal value) {
        if (value == null) {
            throw new UserProfileValidationException("onboarding.averageSleepHours", "must not be null");
        }
        if (value.stripTrailingZeros().scale() > SLEEP_HOURS_SCALE) {
            throw new UserProfileValidationException(
                    "onboarding.averageSleepHours", "must have at most one decimal place");
        }
        if (value.compareTo(MIN_SLEEP_HOURS) < 0 || value.compareTo(MAX_SLEEP_HOURS) > 0) {
            throw new UserProfileValidationException(
                    "onboarding.averageSleepHours", "must be between 0 and 24");
        }
        return value.setScale(SLEEP_HOURS_SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    private static int requireExerciseDays(int value) {
        if (value < MIN_EXERCISE_DAYS || value > MAX_EXERCISE_DAYS) {
            throw new UserProfileValidationException(
                    "onboarding.exerciseDaysPerWeek", "must be between 0 and 7");
        }
        return value;
    }

    private static int requireMeals(int value) {
        if (value < MIN_MEALS || value > MAX_MEALS) {
            throw new UserProfileValidationException("onboarding.mealsPerDay", "must be between 0 and 10");
        }
        return value;
    }

    private static int requireDurationDays(int value) {
        if (!ALLOWED_DURATION_DAYS.contains(value)) {
            throw new UserProfileValidationException(
                    "onboarding.preferredGroupDurationDays", "must be one of 7, 14, 30");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    /** Unmodifiable: callers read the set, they do not edit the entity through it. */
    public Set<InterestCategory> getInterests() {
        return Collections.unmodifiableSet(interests);
    }

    public CoachStyle getCoachStyle() {
        return coachStyle;
    }

    public BigDecimal getAverageSleepHours() {
        return averageSleepHours;
    }

    public int getExerciseDaysPerWeek() {
        return exerciseDaysPerWeek;
    }

    public int getMealsPerDay() {
        return mealsPerDay;
    }

    public int getPreferredGroupDurationDays() {
        return preferredGroupDurationDays;
    }
}

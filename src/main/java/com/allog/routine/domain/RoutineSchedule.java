package com.allog.routine.domain;

import com.allog.common.persistence.BaseTimeEntity;
import com.allog.group.domain.RoutineGroup;
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

import java.time.DayOfWeek;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(
        name = "routine_schedule",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_routine_schedule_group",
                columnNames = "routine_group_id"
        )
)
public class RoutineSchedule extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "routine_group_id", nullable = false)
    private RoutineGroup routineGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 32)
    private ScheduleType scheduleType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "deadline_time", nullable = false)
    private LocalTime deadlineTime;

    @Column(nullable = false, length = 64)
    private String timezone;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "routine_schedule_day",
            joinColumns = @JoinColumn(name = "schedule_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_routine_schedule_day",
                    columnNames = {"schedule_id", "day_of_week"}
            )
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    private Set<DayOfWeek> specificDays = EnumSet.noneOf(DayOfWeek.class);

    protected RoutineSchedule() {
    }

    public RoutineSchedule(
            RoutineGroup routineGroup,
            ScheduleType scheduleType,
            LocalDate startDate,
            LocalDate endDate,
            LocalTime deadlineTime,
            String timezone,
            Set<DayOfWeek> specificDays
    ) {
        this.routineGroup = Objects.requireNonNull(routineGroup, "routineGroup must not be null");
        this.scheduleType = Objects.requireNonNull(scheduleType, "scheduleType must not be null");
        this.startDate = Objects.requireNonNull(startDate, "startDate must not be null");
        this.endDate = Objects.requireNonNull(endDate, "endDate must not be null");
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }
        this.deadlineTime = Objects.requireNonNull(deadlineTime, "deadlineTime must not be null");
        this.timezone = validTimezone(timezone);
        this.specificDays = copyDays(specificDays);
        validateDays();
    }

    public Long getId() {
        return id;
    }

    public RoutineGroup getRoutineGroup() {
        return routineGroup;
    }

    public ScheduleType getScheduleType() {
        return scheduleType;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalTime getDeadlineTime() {
        return deadlineTime;
    }

    public String getTimezone() {
        return timezone;
    }

    public Set<DayOfWeek> getSpecificDays() {
        return Set.copyOf(specificDays);
    }

    private Set<DayOfWeek> copyDays(Set<DayOfWeek> days) {
        Objects.requireNonNull(days, "specificDays must not be null");
        return days.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(days);
    }

    private String validTimezone(String value) {
        Objects.requireNonNull(value, "timezone must not be null");
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("timezone must be a valid ZoneId", exception);
        }
    }

    private void validateDays() {
        if (scheduleType == ScheduleType.DAILY && !specificDays.isEmpty()) {
            throw new IllegalArgumentException("DAILY schedule must not have specific days");
        }
        if (scheduleType == ScheduleType.SPECIFIC_DAYS && specificDays.isEmpty()) {
            throw new IllegalArgumentException("SPECIFIC_DAYS schedule must have at least one day");
        }
    }
}

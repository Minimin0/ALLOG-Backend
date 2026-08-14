package com.allog.routine.schedule;

import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RoutineScheduleCalculator {

    public boolean isScheduledOn(RoutineSchedule schedule, LocalDate date) {
        requireSchedule(schedule);
        Objects.requireNonNull(date, "date must not be null");
        if (date.isBefore(schedule.getStartDate()) || date.isAfter(schedule.getEndDate())) {
            return false;
        }
        return switch (schedule.getScheduleType()) {
            case DAILY -> true;
            case SPECIFIC_DAYS -> schedule.getSpecificDays().contains(date.getDayOfWeek());
        };
    }

    public List<LocalDate> scheduledDates(RoutineSchedule schedule) {
        requireSchedule(schedule);
        List<LocalDate> dates = new ArrayList<>();
        LocalDate date = schedule.getStartDate();
        while (true) {
            if (isScheduledOn(schedule, date)) {
                dates.add(date);
            }
            if (date.equals(schedule.getEndDate())) {
                return List.copyOf(dates);
            }
            date = date.plusDays(1);
        }
    }

    public List<LocalDate> participationEligibleScheduledDates(
            RoutineSchedule schedule,
            Instant participationStartedAt
    ) {
        Instant startedAt = Objects.requireNonNull(
                participationStartedAt,
                "participationStartedAt must not be null"
        );
        return scheduledDates(schedule).stream()
                .filter(date -> startedAt.isBefore(deadlineFor(schedule, date).orElseThrow()))
                .toList();
    }

    public int totalScheduledOpportunityCount(RoutineSchedule schedule) {
        return scheduledDates(schedule).size();
    }

    public Optional<Instant> deadlineFor(RoutineSchedule schedule, LocalDate date) {
        if (!isScheduledOn(schedule, date)) {
            return Optional.empty();
        }
        return Optional.of(date
                .atTime(schedule.getDeadlineTime())
                .atZone(zoneOf(schedule))
                .toInstant());
    }

    public boolean isTodayScheduled(RoutineSchedule schedule, Clock clock) {
        return isScheduledOn(schedule, currentDate(schedule, clock));
    }

    public Optional<Instant> todayDeadline(RoutineSchedule schedule, Clock clock) {
        return deadlineFor(schedule, currentDate(schedule, clock));
    }

    public Optional<Instant> finalScheduledDeadline(RoutineSchedule schedule) {
        List<LocalDate> dates = scheduledDates(schedule);
        return dates.isEmpty() ? Optional.empty() : deadlineFor(schedule, dates.getLast());
    }

    public boolean isDeadlinePassed(Instant deadline, Clock clock) {
        Objects.requireNonNull(deadline, "deadline must not be null");
        return !requireClock(clock).instant().isBefore(deadline);
    }

    public int remainingScheduledOpportunityCount(RoutineSchedule schedule, Clock clock) {
        Instant now = requireClock(clock).instant();
        return (int) scheduledDates(schedule).stream()
                .map(date -> deadlineFor(schedule, date).orElseThrow())
                .filter(now::isBefore)
                .count();
    }

    private LocalDate currentDate(RoutineSchedule schedule, Clock clock) {
        return LocalDate.ofInstant(requireClock(clock).instant(), zoneOf(requireSchedule(schedule)));
    }

    private ZoneId zoneOf(RoutineSchedule schedule) {
        return ZoneId.of(schedule.getTimezone());
    }

    private RoutineSchedule requireSchedule(RoutineSchedule schedule) {
        return Objects.requireNonNull(schedule, "schedule must not be null");
    }

    private Clock requireClock(Clock clock) {
        return Objects.requireNonNull(clock, "clock must not be null");
    }
}

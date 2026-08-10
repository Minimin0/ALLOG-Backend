package com.allog.routine.schedule;

import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutineScheduleCalculatorTest {

    private final RoutineScheduleCalculator calculator = new RoutineScheduleCalculator();

    @Test
    void dailyIncludesBothBoundariesAndCountsActualDates() {
        RoutineSchedule schedule = daily("2026-08-10", "2026-08-16", "Asia/Seoul");

        assertAll(
                () -> assertTrue(calculator.isScheduledOn(schedule, date("2026-08-10"))),
                () -> assertTrue(calculator.isScheduledOn(schedule, date("2026-08-16"))),
                () -> assertFalse(calculator.isScheduledOn(schedule, date("2026-08-09"))),
                () -> assertFalse(calculator.isScheduledOn(schedule, date("2026-08-17"))),
                () -> assertEquals(7, calculator.totalScheduledOpportunityCount(schedule))
        );
    }

    @Test
    void specificDaysReturnsOnlyActualMatchingDates() {
        RoutineSchedule schedule = specific(
                "2026-08-10",
                "2026-08-16",
                "Asia/Seoul",
                DayOfWeek.MONDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY
        );

        assertEquals(
                List.of(date("2026-08-10"), date("2026-08-12"), date("2026-08-14")),
                calculator.scheduledDates(schedule)
        );
    }

    @Test
    void crossesMonthBoundaryWithoutSpecialCalendarLogic() {
        RoutineSchedule schedule = daily("2026-08-29", "2026-09-04", "Asia/Seoul");

        assertEquals(7, calculator.totalScheduledOpportunityCount(schedule));
        assertTrue(calculator.scheduledDates(schedule).contains(date("2026-09-01")));
    }

    @Test
    void crossesYearBoundaryWithoutSpecialCalendarLogic() {
        RoutineSchedule schedule = daily("2026-12-30", "2027-01-03", "Asia/Seoul");

        assertEquals(5, calculator.totalScheduledOpportunityCount(schedule));
        assertTrue(calculator.scheduledDates(schedule).contains(date("2027-01-01")));
    }

    @Test
    void includesLeapDayUsingJavaTime() {
        RoutineSchedule schedule = daily("2028-02-28", "2028-03-01", "Asia/Seoul");

        assertEquals(
                List.of(date("2028-02-28"), date("2028-02-29"), date("2028-03-01")),
                calculator.scheduledDates(schedule)
        );
    }

    @Test
    void convertsSeoulDeadlineToInstant() {
        RoutineSchedule schedule = daily("2026-08-11", "2026-08-11", "Asia/Seoul");

        assertEquals(
                Instant.parse("2026-08-11T14:00:00Z"),
                calculator.deadlineFor(schedule, date("2026-08-11")).orElseThrow()
        );
    }

    @Test
    void letsZoneRulesHandleNewYorkDst() {
        RoutineSchedule schedule = daily("2026-07-01", "2026-07-01", "America/New_York");

        assertEquals(
                Instant.parse("2026-07-02T03:00:00Z"),
                calculator.deadlineFor(schedule, date("2026-07-01")).orElseThrow()
        );
    }

    @Test
    void returnsNoDeadlineForUnscheduledDate() {
        RoutineSchedule schedule = specific(
                "2026-08-10", "2026-08-16", "Asia/Seoul", DayOfWeek.MONDAY
        );

        assertTrue(calculator.deadlineFor(schedule, date("2026-08-11")).isEmpty());
    }

    @Test
    void finalDeadlineUsesLastActualSpecificDay() {
        RoutineSchedule schedule = specific(
                "2026-08-10",
                "2026-08-16",
                "Asia/Seoul",
                DayOfWeek.MONDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY
        );

        assertEquals(
                Instant.parse("2026-08-14T14:00:00Z"),
                calculator.finalScheduledDeadline(schedule).orElseThrow()
        );
    }

    @Test
    void returnsNoFinalDeadlineWhenScheduleHasNoActualDate() {
        RoutineSchedule schedule = specific(
                "2026-08-11", "2026-08-12", "Asia/Seoul", DayOfWeek.MONDAY
        );

        assertTrue(calculator.finalScheduledDeadline(schedule).isEmpty());
    }

    @Test
    void derivesTodayFromScheduleTimezoneNotClockZone() {
        RoutineSchedule schedule = daily("2026-08-11", "2026-08-11", "Asia/Seoul");
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-10T15:30:00Z"),
                ZoneId.of("America/Los_Angeles")
        );

        assertAll(
                () -> assertTrue(calculator.isTodayScheduled(schedule, clock)),
                () -> assertEquals(
                        Instant.parse("2026-08-11T14:00:00Z"),
                        calculator.todayDeadline(schedule, clock).orElseThrow()
                )
        );
    }

    @ParameterizedTest
    @MethodSource("deadlineCases")
    void treatsDeadlineAsClosedAtExactInstant(Instant now, boolean passed, int remaining) {
        RoutineSchedule schedule = daily("2026-08-11", "2026-08-11", "Asia/Seoul");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Instant deadline = calculator.deadlineFor(schedule, date("2026-08-11")).orElseThrow();

        assertAll(
                () -> assertEquals(passed, calculator.isDeadlinePassed(deadline, clock)),
                () -> assertEquals(remaining, calculator.remainingScheduledOpportunityCount(schedule, clock))
        );
    }

    @Test
    void ignoresUnscheduledTodayAndCountsOnlyFutureScheduledDates() {
        RoutineSchedule schedule = specific(
                "2026-08-10",
                "2026-08-16",
                "Asia/Seoul",
                DayOfWeek.MONDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY
        );
        Clock tuesday = seoulClock("2026-08-11T12:00:00Z");

        assertAll(
                () -> assertFalse(calculator.isTodayScheduled(schedule, tuesday)),
                () -> assertTrue(calculator.todayDeadline(schedule, tuesday).isEmpty()),
                () -> assertEquals(2, calculator.remainingScheduledOpportunityCount(schedule, tuesday))
        );
    }

    @Test
    void beforeStartCountsEveryScheduledOpportunity() {
        RoutineSchedule schedule = specific(
                "2026-08-10",
                "2026-08-16",
                "Asia/Seoul",
                DayOfWeek.MONDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY
        );
        Clock beforeStart = seoulClock("2026-08-08T12:00:00Z");

        assertAll(
                () -> assertFalse(calculator.isTodayScheduled(schedule, beforeStart)),
                () -> assertEquals(3, calculator.remainingScheduledOpportunityCount(schedule, beforeStart))
        );
    }

    @Test
    void afterEndHasNoRemainingOpportunity() {
        RoutineSchedule schedule = daily("2026-08-10", "2026-08-16", "Asia/Seoul");
        Clock afterEnd = seoulClock("2026-08-17T12:00:00Z");

        assertAll(
                () -> assertFalse(calculator.isTodayScheduled(schedule, afterEnd)),
                () -> assertEquals(0, calculator.remainingScheduledOpportunityCount(schedule, afterEnd))
        );
    }

    private static Stream<Arguments> deadlineCases() {
        return Stream.of(
                Arguments.of(Instant.parse("2026-08-11T13:59:59Z"), false, 1),
                Arguments.of(Instant.parse("2026-08-11T14:00:00Z"), true, 0),
                Arguments.of(Instant.parse("2026-08-11T14:00:01Z"), true, 0)
        );
    }

    private RoutineSchedule daily(String start, String end, String timezone) {
        return schedule(ScheduleType.DAILY, start, end, timezone, Set.of());
    }

    private RoutineSchedule specific(String start, String end, String timezone, DayOfWeek... days) {
        return schedule(ScheduleType.SPECIFIC_DAYS, start, end, timezone, Set.of(days));
    }

    private RoutineSchedule schedule(
            ScheduleType type,
            String start,
            String end,
            String timezone,
            Set<DayOfWeek> days
    ) {
        User creator = User.create();
        RoutineDefinition definition = new RoutineDefinition("물 마시기", null);
        RoutineGroup group = new RoutineGroup(
                definition,
                creator,
                "건강한 물 마시기",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.DRAFT,
                5,
                3
        );
        return new RoutineSchedule(
                group,
                type,
                date(start),
                date(end),
                LocalTime.of(23, 0),
                timezone,
                days
        );
    }

    private Clock seoulClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Seoul"));
    }

    private LocalDate date(String value) {
        return LocalDate.parse(value);
    }
}

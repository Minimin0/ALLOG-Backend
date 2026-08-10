package com.allog.core;

import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreDomainValidationTest {

    @Test
    void rejectsInvalidGroupCounts() {
        assertThrows(IllegalArgumentException.class, () -> group(0, 1));
        assertThrows(IllegalArgumentException.class, () -> group(5, 0));
    }

    @Test
    void rejectsScheduleWithReversedDates() {
        assertThrows(IllegalArgumentException.class, () -> schedule(
                ScheduleType.DAILY,
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 1),
                Set.of()
        ));
    }

    @Test
    void rejectsSpecificDaysWithoutDay() {
        assertThrows(IllegalArgumentException.class, () -> schedule(
                ScheduleType.SPECIFIC_DAYS,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 7),
                Set.of()
        ));
    }

    @Test
    void rejectsDailyWithSpecificDay() {
        assertThrows(IllegalArgumentException.class, () -> schedule(
                ScheduleType.DAILY,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 7),
                Set.of(DayOfWeek.MONDAY)
        ));
    }

    @Test
    void rejectsInvalidTimezone() {
        assertThrows(IllegalArgumentException.class, () -> new RoutineSchedule(
                group(5, 3),
                ScheduleType.DAILY,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 7),
                LocalTime.NOON,
                "Not/A-Timezone",
                Set.of()
        ));
    }

    private RoutineSchedule schedule(
            ScheduleType type,
            LocalDate startDate,
            LocalDate endDate,
            Set<DayOfWeek> days
    ) {
        return new RoutineSchedule(
                group(5, 3),
                type,
                startDate,
                endDate,
                LocalTime.NOON,
                "Asia/Seoul",
                days
        );
    }

    private RoutineGroup group(int maxMembers, int requiredCount) {
        return new RoutineGroup(
                new RoutineDefinition("물 마시기", null),
                User.create(),
                "건강한 물 마시기",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.DRAFT,
                maxMembers,
                requiredCount
        );
    }
}

package com.allog.core;

import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineKey;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CoreDomainValidationTest {

    @Test
    void normalizesAndValidatesStableRoutineKeyIndependentlyFromDisplayName() {
        RoutineKey key = new RoutineKey(" test_routine_1 ");
        RoutineDefinition first = new RoutineDefinition(key, "첫 이름", null);
        RoutineDefinition renamedDisplay = new RoutineDefinition(key, "바뀐 이름", null);

        assertAll(
                () -> assertEquals("TEST_ROUTINE_1", key.value()),
                () -> assertEquals(key, first.getRoutineKey()),
                () -> assertEquals(key, renamedDisplay.getRoutineKey()),
                () -> assertEquals(64, new RoutineKey("A".repeat(64)).value().length()),
                () -> assertThrows(NullPointerException.class, () -> new RoutineKey(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new RoutineKey("")),
                () -> assertThrows(IllegalArgumentException.class, () -> new RoutineKey("_TEST")),
                () -> assertThrows(IllegalArgumentException.class, () -> new RoutineKey("TEST-ROUTINE")),
                () -> assertThrows(IllegalArgumentException.class, () -> new RoutineKey("A".repeat(65))),
                () -> assertFalse(java.util.Arrays.stream(RoutineDefinition.class.getMethods())
                        .anyMatch(method -> method.getName().equals("setRoutineKey")))
        );
    }

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

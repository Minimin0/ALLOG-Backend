package com.allog.group.dto;

import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

public record PublicRoutineGroupsResponse(List<Item> items) {

    public PublicRoutineGroupsResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }

    public record Item(
            Long groupId,
            String name,
            Routine routine,
            RoutineGroupStatus status,
            GroupVisibility visibility,
            int maxMembers,
            long currentMembers,
            int requiredCompletionCount,
            Schedule schedule
    ) {
        public static Item from(RoutineGroup group, long currentMembers, RoutineSchedule schedule) {
            return new Item(
                    group.getId(),
                    group.getName(),
                    new Routine(group.getRoutineDefinition().getId(), group.getRoutineDefinition().getName(),
                            group.getRoutineDefinition().getDescription()),
                    group.getStatus(),
                    group.getVisibility(),
                    group.getMaxMembers(),
                    currentMembers,
                    group.getRequiredCompletionCount(),
                    Schedule.from(schedule)
            );
        }
    }

    public record Routine(Long routineDefinitionId, String name, String description) {
    }

    public record Schedule(
            ScheduleType scheduleType,
            LocalDate startDate,
            LocalDate endDate,
            LocalTime deadlineTime,
            String timezone,
            List<DayOfWeek> specificDays
    ) {
        private static Schedule from(RoutineSchedule schedule) {
            return new Schedule(
                    schedule.getScheduleType(),
                    schedule.getStartDate(),
                    schedule.getEndDate(),
                    schedule.getDeadlineTime(),
                    schedule.getTimezone(),
                    schedule.getSpecificDays().stream().sorted().toList()
            );
        }
    }
}

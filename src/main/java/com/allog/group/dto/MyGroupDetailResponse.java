package com.allog.group.dto;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record MyGroupDetailResponse(
        Group group,
        Routine routine,
        Schedule schedule,
        Membership membership
) {

    public static MyGroupDetailResponse from(GroupMember membership, RoutineSchedule schedule, int currentMembers) {
        RoutineGroup group = membership.getRoutineGroup();
        return new MyGroupDetailResponse(
                Group.from(group, currentMembers),
                Routine.from(group.getRoutineDefinition()),
                schedule == null ? null : Schedule.from(schedule),
                Membership.from(membership)
        );
    }

    public record Group(
            Long groupId,
            String name,
            GroupVisibility visibility,
            RoutineGroupStatus status,
            int maxMembers,
            int currentMembers,
            int requiredCompletionCount
    ) {

        private static Group from(RoutineGroup group, int currentMembers) {
            return new Group(
                    group.getId(),
                    group.getName(),
                    group.getVisibility(),
                    group.getStatus(),
                    group.getMaxMembers(),
                    currentMembers,
                    group.getRequiredCompletionCount()
            );
        }
    }

    public record Routine(String name, String description) {

        private static Routine from(RoutineDefinition routine) {
            return new Routine(routine.getName(), routine.getDescription());
        }
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

    public record Membership(GroupMemberRole myRole, GroupMemberStatus myStatus) {

        private static Membership from(GroupMember membership) {
            return new Membership(membership.getRole(), membership.getStatus());
        }
    }
}

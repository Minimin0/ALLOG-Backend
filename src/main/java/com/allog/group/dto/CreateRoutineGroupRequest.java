package com.allog.group.dto;

import com.allog.group.domain.GroupVisibility;
import com.allog.routine.domain.ScheduleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Transport-level validation only. Domain invariants stay in the entities and value objects, so the
 * verification template key is deliberately not pattern-checked here: {@code VerificationTemplateKey}
 * owns that rule. A null key means a record-only group; an invalid key is rejected, never downgraded.
 */
public record CreateRoutineGroupRequest(
        @NotNull @Positive Long routineDefinitionId,
        @NotBlank @Size(max = 100) String name,
        @NotNull GroupVisibility visibility,
        @Positive int maxMembers,
        @Positive int requiredCompletionCount,
        @NotNull @Valid Schedule schedule,
        String verificationTemplateKey
) {

    public record Schedule(
            @NotNull ScheduleType scheduleType,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull LocalTime deadlineTime,
            @NotBlank String timezone,
            Set<DayOfWeek> specificDays
    ) {

        public Schedule {
            specificDays = specificDays == null ? Set.of() : Set.copyOf(specificDays);
        }
    }
}

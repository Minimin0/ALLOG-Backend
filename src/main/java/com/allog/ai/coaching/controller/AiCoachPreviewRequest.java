package com.allog.ai.coaching.controller;

import com.allog.ai.coaching.dto.ProgressAnalysisInput;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record AiCoachPreviewRequest(
        @NotBlank @Size(max = 100) String challengeName,
        @NotNull Boolean todayScheduled,
        @NotNull Boolean todayCompleted,
        @NotNull Boolean todayVerificationPending,
        @NotNull @PositiveOrZero Integer completedCount,
        @NotNull @Positive Integer requiredCompletionCount,
        @NotNull @PositiveOrZero Integer currentStreak,
        @NotNull @PositiveOrZero Integer previousBestStreak,
        @NotNull @PositiveOrZero Integer remainingOpportunityCount,
        @NotNull @PositiveOrZero Integer pendingDecisionCount,
        @DecimalMin("0.0") @DecimalMax("1.0") Double groupCompletionRate,
        @DecimalMin("0.0") @DecimalMax("1.0") Double previousChallengeCompletionRate,
        Instant certificationDeadline,
        @NotNull Boolean challengeCompleted
) {

    ProgressAnalysisInput toProgressInput() {
        return new ProgressAnalysisInput(
                todayScheduled,
                todayCompleted,
                todayVerificationPending,
                completedCount,
                requiredCompletionCount,
                currentStreak,
                previousBestStreak,
                remainingOpportunityCount,
                pendingDecisionCount,
                groupCompletionRate,
                previousChallengeCompletionRate,
                certificationDeadline,
                challengeCompleted
        );
    }
}

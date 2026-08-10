package com.allog.ai.coaching.dto;

import com.allog.ai.coaching.domain.CompletionRiskLevel;

import java.time.Instant;

public record ProgressSnapshot(
        boolean todayScheduled,
        boolean todayCompleted,
        boolean todayVerificationPending,
        int completedCount,
        int requiredCompletionCount,
        double personalCompletionRate,
        int currentStreak,
        int previousBestStreak,
        int remainingRequiredCount,
        int remainingOpportunityCount,
        int pendingDecisionCount,
        long potentialCompletionCapacity,
        CompletionRiskLevel completionRiskLevel,
        Double groupCompletionRate,
        Double previousChallengeCompletionRate,
        Instant certificationDeadline,
        Long minutesUntilDeadline,
        boolean deadlinePassed,
        boolean challengeCompleted,
        Instant analyzedAt
) {
}

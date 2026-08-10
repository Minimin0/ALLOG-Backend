package com.allog.ai.coaching.dto;

import java.time.Instant;

public record ProgressAnalysisInput(
        boolean todayScheduled,
        boolean todayCompleted,
        boolean todayVerificationPending,
        int completedCount,
        int requiredCompletionCount,
        int currentStreak,
        int previousBestStreak,
        int remainingOpportunityCount,
        int pendingDecisionCount,
        Double groupCompletionRate,
        Double previousChallengeCompletionRate,
        Instant certificationDeadline,
        boolean challengeCompleted
) {

    public ProgressAnalysisInput {
        requireNonNegative(completedCount, "completedCount");
        if (requiredCompletionCount <= 0) {
            throw new IllegalArgumentException("requiredCompletionCount must be positive");
        }
        requireNonNegative(currentStreak, "currentStreak");
        requireNonNegative(previousBestStreak, "previousBestStreak");
        requireNonNegative(remainingOpportunityCount, "remainingOpportunityCount");
        requireNonNegative(pendingDecisionCount, "pendingDecisionCount");
        if (todayCompleted && !todayScheduled) {
            throw new IllegalArgumentException("todayCompleted requires a scheduled today");
        }
        if (todayVerificationPending
                && (!todayScheduled || todayCompleted || pendingDecisionCount == 0)) {
            throw new IllegalArgumentException(
                    "todayVerificationPending requires an incomplete scheduled today and a pending decision"
            );
        }
        requireRate(groupCompletionRate, "groupCompletionRate");
        requireRate(previousChallengeCompletionRate, "previousChallengeCompletionRate");
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireRate(Double value, String name) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 1)) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0");
        }
    }
}

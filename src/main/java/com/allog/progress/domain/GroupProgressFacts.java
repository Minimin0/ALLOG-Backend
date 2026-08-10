package com.allog.progress.domain;

public record GroupProgressFacts(
        int eligibleMemberCount,
        long completedRequirementCount,
        long totalRequiredCount,
        double groupCompletionRate,
        long pendingDecisionCount,
        int goalAchievedMemberCount
) {

    public GroupProgressFacts {
        if (eligibleMemberCount <= 0) {
            throw new IllegalArgumentException("eligibleMemberCount must be positive");
        }
        if (completedRequirementCount < 0 || totalRequiredCount <= 0
                || completedRequirementCount > totalRequiredCount) {
            throw new IllegalArgumentException("group completion counts are invalid");
        }
        if (!Double.isFinite(groupCompletionRate)
                || groupCompletionRate < 0
                || groupCompletionRate > 1) {
            throw new IllegalArgumentException("groupCompletionRate must be between 0.0 and 1.0");
        }
        if (pendingDecisionCount < 0) {
            throw new IllegalArgumentException("pendingDecisionCount must not be negative");
        }
        if (goalAchievedMemberCount < 0 || goalAchievedMemberCount > eligibleMemberCount) {
            throw new IllegalArgumentException("goalAchievedMemberCount is invalid");
        }
    }
}

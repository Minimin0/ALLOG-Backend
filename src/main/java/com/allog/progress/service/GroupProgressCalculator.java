package com.allog.progress.service;

import com.allog.progress.domain.GroupProgressFacts;
import com.allog.progress.domain.PersonalProgressFacts;

import java.util.List;
import java.util.Objects;

public final class GroupProgressCalculator {

    public GroupProgressFacts calculate(List<PersonalProgressFacts> eligibleProgressFacts) {
        Objects.requireNonNull(eligibleProgressFacts, "eligibleProgressFacts must not be null");
        if (eligibleProgressFacts.isEmpty()) {
            throw new IllegalArgumentException("at least one eligible member is required");
        }

        long completedRequirementCount = 0;
        long totalRequiredCount = 0;
        long pendingDecisionCount = 0;
        int goalAchievedMemberCount = 0;
        for (PersonalProgressFacts facts : eligibleProgressFacts) {
            Objects.requireNonNull(facts, "personal progress facts must not be null");
            completedRequirementCount = Math.addExact(
                    completedRequirementCount,
                    Math.min(facts.completedCount(), facts.requiredCompletionCount())
            );
            totalRequiredCount = Math.addExact(totalRequiredCount, facts.requiredCompletionCount());
            pendingDecisionCount = Math.addExact(pendingDecisionCount, facts.pendingDecisionCount());
            if (facts.completedCount() >= facts.requiredCompletionCount()) {
                goalAchievedMemberCount++;
            }
        }

        return new GroupProgressFacts(
                eligibleProgressFacts.size(),
                completedRequirementCount,
                totalRequiredCount,
                (double) completedRequirementCount / totalRequiredCount,
                pendingDecisionCount,
                goalAchievedMemberCount
        );
    }
}

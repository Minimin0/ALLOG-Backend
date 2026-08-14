package com.allog.ai.coaching.policy;

import com.allog.ai.coaching.domain.InsightType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

public record AiCoachPolicy(
        int deadlineApproachingMinutes,
        int streakContinuingCount,
        double completionRiskMediumRatio,
        double groupGoalNearRate,
        Map<InsightType, Integer> priorities
) {

    public AiCoachPolicy {
        if (deadlineApproachingMinutes <= 0 || streakContinuingCount <= 0) {
            throw new IllegalArgumentException("time and streak thresholds must be positive");
        }
        if (!Double.isFinite(completionRiskMediumRatio)
                || completionRiskMediumRatio <= 0
                || completionRiskMediumRatio >= 1) {
            throw new IllegalArgumentException("completionRiskMediumRatio must be between 0.0 and 1.0");
        }
        if (!Double.isFinite(groupGoalNearRate) || groupGoalNearRate < 0 || groupGoalNearRate > 1) {
            throw new IllegalArgumentException("groupGoalNearRate must be between 0.0 and 1.0");
        }

        Objects.requireNonNull(priorities, "priorities must not be null");
        EnumMap<InsightType, Integer> copy = new EnumMap<>(InsightType.class);
        copy.putAll(priorities);
        if (!copy.keySet().containsAll(EnumSet.allOf(InsightType.class))
                || copy.values().stream().anyMatch(priority -> priority == null || priority <= 0)) {
            throw new IllegalArgumentException("every insight must have a positive priority");
        }
        priorities = Map.copyOf(copy);
    }

    public static AiCoachPolicy defaults() {
        return new AiCoachPolicy(
                120,
                3,
                0.7,
                0.8,
                Map.of(
                        InsightType.VERIFICATION_PENDING, 1,
                        InsightType.DEADLINE_APPROACHING, 2,
                        InsightType.COMPLETION_RISK, 3,
                        InsightType.GROUP_GOAL_NEAR, 4,
                        InsightType.STREAK_RECORD, 5,
                        InsightType.STREAK_CONTINUING, 6,
                        InsightType.IMPROVED_FROM_PREVIOUS, 7,
                        InsightType.TODAY_NOT_COMPLETED, 8
                )
        );
    }

    public int priorityOf(InsightType type) {
        return priorities.get(Objects.requireNonNull(type, "type must not be null"));
    }
}

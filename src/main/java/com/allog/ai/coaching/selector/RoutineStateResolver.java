package com.allog.ai.coaching.selector;

import com.allog.ai.coaching.domain.CompletionRiskLevel;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.ProgressInsight;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.dto.ProgressSnapshot;

import java.util.List;
import java.util.Objects;

public final class RoutineStateResolver {

    public RoutineState resolve(ProgressSnapshot snapshot, List<ProgressInsight> insights) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(insights, "insights must not be null");
        if (snapshot.challengeCompleted()) {
            return RoutineState.COMPLETED;
        }
        if (snapshot.completionRiskLevel() == CompletionRiskLevel.HIGH) {
            return RoutineState.AT_RISK;
        }
        if (snapshot.completionRiskLevel() == CompletionRiskLevel.MEDIUM
                || has(insights, InsightType.DEADLINE_APPROACHING)
                || has(insights, InsightType.TODAY_NOT_COMPLETED)) {
            return RoutineState.ATTENTION;
        }
        return RoutineState.GOOD;
    }

    private boolean has(List<ProgressInsight> insights, InsightType type) {
        return insights.stream().anyMatch(insight -> insight.type() == type);
    }
}

package com.allog.ai.coaching.detector;

import com.allog.ai.coaching.domain.CompletionRiskLevel;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.ProgressInsight;
import com.allog.ai.coaching.dto.ProgressSnapshot;
import com.allog.ai.coaching.policy.AiCoachPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProgressInsightDetector {

    public List<ProgressInsight> detect(ProgressSnapshot snapshot, AiCoachPolicy policy) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (snapshot.challengeCompleted()) {
            return List.of();
        }

        List<ProgressInsight> insights = new ArrayList<>();
        if (snapshot.todayVerificationPending()) {
            add(insights, InsightType.VERIFICATION_PENDING, policy);
        }
        if (snapshot.todayScheduled()
                && !snapshot.todayCompleted()
                && !snapshot.todayVerificationPending()
                && snapshot.minutesUntilDeadline() != null
                && !snapshot.deadlinePassed()
                && snapshot.minutesUntilDeadline() <= policy.deadlineApproachingMinutes()) {
            add(insights, InsightType.DEADLINE_APPROACHING, policy);
        }
        if (snapshot.completionRiskLevel() != CompletionRiskLevel.LOW) {
            add(insights, InsightType.COMPLETION_RISK, policy);
        }
        if (snapshot.groupCompletionRate() != null
                && snapshot.groupCompletionRate() >= policy.groupGoalNearRate()) {
            add(insights, InsightType.GROUP_GOAL_NEAR, policy);
        }
        if (snapshot.currentStreak() > snapshot.previousBestStreak()
                && snapshot.currentStreak() >= policy.streakContinuingCount()) {
            add(insights, InsightType.STREAK_RECORD, policy);
        }
        if (snapshot.currentStreak() >= policy.streakContinuingCount()) {
            add(insights, InsightType.STREAK_CONTINUING, policy);
        }
        if (snapshot.previousChallengeCompletionRate() != null
                && snapshot.personalCompletionRate() > snapshot.previousChallengeCompletionRate()) {
            add(insights, InsightType.IMPROVED_FROM_PREVIOUS, policy);
        }
        if (snapshot.todayScheduled()
                && !snapshot.todayCompleted()
                && !snapshot.todayVerificationPending()) {
            add(insights, InsightType.TODAY_NOT_COMPLETED, policy);
        }
        return List.copyOf(insights);
    }

    private void add(List<ProgressInsight> insights, InsightType type, AiCoachPolicy policy) {
        insights.add(new ProgressInsight(type, policy.priorityOf(type)));
    }
}

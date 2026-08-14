package com.allog.ai.coaching.analyzer;

import com.allog.ai.coaching.domain.CompletionRiskLevel;
import com.allog.ai.coaching.dto.ProgressAnalysisInput;
import com.allog.ai.coaching.dto.ProgressSnapshot;
import com.allog.ai.coaching.policy.AiCoachPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ProgressAnalyzer {

    public ProgressSnapshot analyze(ProgressAnalysisInput input, AiCoachPolicy policy, Clock clock) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Instant now = Objects.requireNonNull(clock, "clock must not be null").instant();
        int remainingRequiredCount = Math.max(input.requiredCompletionCount() - input.completedCount(), 0);
        double completionRate = Math.min((double) input.completedCount() / input.requiredCompletionCount(), 1.0);
        long potentialCompletionCapacity = (long) input.pendingDecisionCount()
                + input.remainingOpportunityCount();
        CompletionRiskLevel riskLevel = completionRisk(
                remainingRequiredCount,
                potentialCompletionCapacity,
                policy.completionRiskMediumRatio()
        );
        Instant deadline = input.certificationDeadline();
        boolean deadlinePassed = deadline != null && !now.isBefore(deadline);
        Long minutesUntilDeadline = deadline == null
                ? null
                : deadlinePassed ? 0L : roundedUpMinutes(Duration.between(now, deadline));

        return new ProgressSnapshot(
                input.todayScheduled(),
                input.todayCompleted(),
                input.todayVerificationPending(),
                input.completedCount(),
                input.requiredCompletionCount(),
                completionRate,
                input.currentStreak(),
                input.previousBestStreak(),
                remainingRequiredCount,
                input.remainingOpportunityCount(),
                input.pendingDecisionCount(),
                potentialCompletionCapacity,
                riskLevel,
                input.groupCompletionRate(),
                input.previousChallengeCompletionRate(),
                deadline,
                minutesUntilDeadline,
                deadlinePassed,
                input.challengeCompleted(),
                now
        );
    }

    private CompletionRiskLevel completionRisk(int required, long potentialCapacity, double mediumRatio) {
        if (required == 0) {
            return CompletionRiskLevel.LOW;
        }
        if (required >= potentialCapacity) {
            return CompletionRiskLevel.HIGH;
        }
        return (double) required / potentialCapacity >= mediumRatio
                ? CompletionRiskLevel.MEDIUM
                : CompletionRiskLevel.LOW;
    }

    private long roundedUpMinutes(Duration duration) {
        long minutes = duration.toMinutes();
        return duration.minusMinutes(minutes).isZero() ? minutes : minutes + 1;
    }
}

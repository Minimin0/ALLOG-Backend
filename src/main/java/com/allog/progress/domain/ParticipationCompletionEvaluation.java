package com.allog.progress.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ParticipationCompletionEvaluation(
        boolean goalAchieved,
        boolean scheduleEnded,
        boolean hasPendingDecision,
        boolean finalizationReady,
        Instant finalScheduledDeadline,
        Optional<Outcome> recommendedOutcome
) {

    public ParticipationCompletionEvaluation {
        Objects.requireNonNull(finalScheduledDeadline, "finalScheduledDeadline must not be null");
        recommendedOutcome = Objects.requireNonNull(
                recommendedOutcome,
                "recommendedOutcome must not be null"
        );
        if (finalizationReady != (scheduleEnded && !hasPendingDecision)) {
            throw new IllegalArgumentException("finalizationReady is inconsistent");
        }
        if (recommendedOutcome.isPresent() != finalizationReady) {
            throw new IllegalArgumentException("recommendedOutcome requires finalization readiness");
        }
        if (recommendedOutcome.isPresent()
                && (recommendedOutcome.orElseThrow() == Outcome.COMPLETED) != goalAchieved) {
            throw new IllegalArgumentException("recommendedOutcome does not match goal achievement");
        }
    }

    public enum Outcome {
        COMPLETED,
        FAILED
    }
}

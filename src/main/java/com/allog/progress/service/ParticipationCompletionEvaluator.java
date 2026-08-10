package com.allog.progress.service;

import com.allog.progress.domain.ParticipationCompletionEvaluation;
import com.allog.progress.domain.ParticipationCompletionEvaluation.Outcome;
import com.allog.progress.domain.PersonalProgressFacts;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.schedule.RoutineScheduleCalculator;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class ParticipationCompletionEvaluator {

    private final RoutineScheduleCalculator scheduleCalculator = new RoutineScheduleCalculator();

    public ParticipationCompletionEvaluation evaluate(
            PersonalProgressFacts progressFacts,
            RoutineSchedule routineSchedule,
            Clock clock
    ) {
        Objects.requireNonNull(progressFacts, "progressFacts must not be null");
        Objects.requireNonNull(routineSchedule, "routineSchedule must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        if (progressFacts.requiredCompletionCount()
                != routineSchedule.getRoutineGroup().getRequiredCompletionCount()) {
            throw new IllegalArgumentException("progress facts and schedule have different requirements");
        }

        Instant finalDeadline = scheduleCalculator.finalScheduledDeadline(routineSchedule)
                .orElseThrow(() -> new IllegalStateException("routine schedule has no scheduled opportunity"));
        boolean goalAchieved = progressFacts.completedCount() >= progressFacts.requiredCompletionCount();
        boolean scheduleEnded = scheduleCalculator.isDeadlinePassed(finalDeadline, clock);
        boolean hasPendingDecision = progressFacts.pendingDecisionCount() > 0;
        boolean finalizationReady = scheduleEnded && !hasPendingDecision;
        Optional<Outcome> outcome = finalizationReady
                ? Optional.of(goalAchieved ? Outcome.COMPLETED : Outcome.FAILED)
                : Optional.empty();

        return new ParticipationCompletionEvaluation(
                goalAchieved,
                scheduleEnded,
                hasPendingDecision,
                finalizationReady,
                finalDeadline,
                outcome
        );
    }
}

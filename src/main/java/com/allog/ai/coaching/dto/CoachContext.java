package com.allog.ai.coaching.dto;

import com.allog.ai.coaching.domain.CompletionRiskLevel;
import com.allog.ai.coaching.domain.FollowUpQuestion;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.ProgressInsight;
import com.allog.ai.coaching.domain.RoutineState;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record CoachContext(
        Challenge challenge,
        Progress progress,
        Group group,
        Deadline deadline,
        SelectedInsight insight,
        RoutineState routineState,
        FollowUp followUp
) {

    public CoachContext {
        Objects.requireNonNull(challenge, "challenge must not be null");
        Objects.requireNonNull(progress, "progress must not be null");
        Objects.requireNonNull(group, "group must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        Objects.requireNonNull(routineState, "routineState must not be null");
    }

    public static CoachContext from(
            String challengeName,
            ProgressSnapshot snapshot,
            Optional<ProgressInsight> selectedInsight,
            RoutineState routineState
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(selectedInsight, "selectedInsight must not be null");
        ProgressInsight selected = selectedInsight.orElse(null);

        return new CoachContext(
                new Challenge(challengeName),
                new Progress(
                        snapshot.todayScheduled(),
                        snapshot.todayCompleted(),
                        snapshot.todayVerificationPending(),
                        snapshot.personalCompletionRate(),
                        snapshot.currentStreak(),
                        snapshot.remainingRequiredCount(),
                        snapshot.remainingOpportunityCount(),
                        snapshot.pendingDecisionCount(),
                        snapshot.completionRiskLevel(),
                        snapshot.challengeCompleted()
                ),
                new Group(snapshot.groupCompletionRate()),
                new Deadline(
                        snapshot.certificationDeadline(),
                        snapshot.minutesUntilDeadline(),
                        snapshot.deadlinePassed()
                ),
                selected == null ? null : new SelectedInsight(selected.type(), selected.priority()),
                routineState,
                null
        );
    }

    public CoachContext withFollowUp(FollowUpQuestion question) {
        Objects.requireNonNull(question, "question must not be null");
        return new CoachContext(
                challenge,
                progress,
                group,
                deadline,
                insight,
                routineState,
                new FollowUp(question.name(), question.instruction())
        );
    }

    public record Challenge(String name) {
        public Challenge {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("challenge name must not be blank");
            }
        }
    }

    public record Progress(
            boolean todayScheduled,
            boolean todayCompleted,
            boolean todayVerificationPending,
            double completionRate,
            int currentStreak,
            int remainingRequiredCount,
            int remainingOpportunityCount,
            int pendingDecisionCount,
            CompletionRiskLevel riskLevel,
            boolean challengeCompleted
    ) {
    }

    public record Group(Double completionRate) {
    }

    public record Deadline(Instant at, Long minutesRemaining, boolean passed) {
    }

    public record SelectedInsight(InsightType type, int priority) {
    }

    public record FollowUp(String id, String instruction) {

        public FollowUp {
            if (id == null || id.isBlank() || instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("follow-up intent must not be blank");
            }
        }
    }
}

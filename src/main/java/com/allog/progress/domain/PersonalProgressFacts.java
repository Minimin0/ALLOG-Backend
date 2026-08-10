package com.allog.progress.domain;

import com.allog.group.domain.GroupMemberStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PersonalProgressFacts(
        boolean todayScheduled,
        boolean todayCompleted,
        boolean todayVerificationPending,
        int completedCount,
        int requiredCompletionCount,
        int currentStreak,
        int previousBestStreak,
        int remainingOpportunityCount,
        int pendingDecisionCount,
        Optional<Instant> certificationDeadline,
        GroupMemberStatus participationStatus
) {

    public PersonalProgressFacts {
        requireNonNegative(completedCount, "completedCount");
        requirePositive(requiredCompletionCount, "requiredCompletionCount");
        requireNonNegative(currentStreak, "currentStreak");
        requireNonNegative(previousBestStreak, "previousBestStreak");
        requireNonNegative(remainingOpportunityCount, "remainingOpportunityCount");
        requireNonNegative(pendingDecisionCount, "pendingDecisionCount");
        certificationDeadline = Objects.requireNonNull(
                certificationDeadline,
                "certificationDeadline must not be null"
        );
        if (todayCompleted && !todayScheduled) {
            throw new IllegalArgumentException("todayCompleted requires a scheduled today");
        }
        if (todayVerificationPending
                && (!todayScheduled || todayCompleted || pendingDecisionCount == 0)) {
            throw new IllegalArgumentException(
                    "todayVerificationPending requires an incomplete scheduled today and a pending decision"
            );
        }
        if (todayScheduled != certificationDeadline.isPresent()) {
            throw new IllegalArgumentException("today schedule and certification deadline must agree");
        }
        participationStatus = Objects.requireNonNull(
                participationStatus,
                "participationStatus must not be null"
        );
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

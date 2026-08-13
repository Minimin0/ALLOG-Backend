package com.allog.progress.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.RoutineGroup;
import com.allog.progress.domain.PersonalProgressFacts;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.schedule.RoutineScheduleCalculator;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PersonalProgressCalculator {

    private final RoutineScheduleCalculator scheduleCalculator = new RoutineScheduleCalculator();

    public PersonalProgressFacts calculate(
            GroupMember groupMember,
            RoutineSchedule routineSchedule,
            List<Verification> verifications,
            Clock clock
    ) {
        Objects.requireNonNull(groupMember, "groupMember must not be null");
        Objects.requireNonNull(routineSchedule, "routineSchedule must not be null");
        Objects.requireNonNull(verifications, "verifications must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        if (!sameGroup(groupMember.getRoutineGroup(), routineSchedule.getRoutineGroup())) {
            throw new IllegalArgumentException("groupMember and routineSchedule must belong to the same group");
        }
        Instant participationStartedAt = groupMember.getParticipationStartedAt();
        if (participationStartedAt == null) {
            throw new IllegalStateException("full personal progress requires participationStartedAt");
        }

        Instant now = clock.instant();
        Clock fixedClock = Clock.fixed(now, clock.getZone());
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(routineSchedule.getTimezone()));
        List<LocalDate> scheduledDates = scheduleCalculator.participationEligibleScheduledDates(
                routineSchedule,
                participationStartedAt
        );
        int requiredCompletionCount = routineSchedule.getRoutineGroup().getRequiredCompletionCount();
        if (requiredCompletionCount > scheduledDates.size()) {
            throw new IllegalStateException(
                    "requiredCompletionCount must not exceed scheduled opportunity count"
            );
        }

        Map<LocalDate, Verification> verificationByDate = indexVerifications(
                groupMember,
                routineSchedule,
                verifications,
                new HashSet<>(scheduledDates),
                today
        );

        int completedCount = 0;
        int remainingOpportunityCount = 0;
        int pendingDecisionCount = 0;
        int currentStreak = 0;
        int previousBestStreak = 0;

        for (LocalDate scheduledDate : scheduledDates) {
            OpportunityOutcome outcome = outcome(
                    routineSchedule,
                    scheduledDate,
                    verificationByDate.get(scheduledDate),
                    today,
                    fixedClock
            );
            switch (outcome) {
                case SUCCESS -> {
                    completedCount++;
                    currentStreak++;
                }
                case FAILED -> {
                    previousBestStreak = Math.max(previousBestStreak, currentStreak);
                    currentStreak = 0;
                }
                case OPEN, FUTURE -> remainingOpportunityCount++;
                case PENDING_DECISION -> pendingDecisionCount++;
            }
        }

        boolean todayScheduled = scheduledDates.contains(today);
        Verification todayVerification = verificationByDate.get(today);
        boolean todayCompleted = todayScheduled
                && todayVerification != null
                && todayVerification.getStatus().countsAsProgress();
        boolean todayVerificationPending = todayScheduled
                && todayVerification != null
                && isPendingDecision(todayVerification.getStatus());

        return new PersonalProgressFacts(
                todayScheduled,
                todayCompleted,
                todayVerificationPending,
                completedCount,
                requiredCompletionCount,
                currentStreak,
                previousBestStreak,
                remainingOpportunityCount,
                pendingDecisionCount,
                todayScheduled
                        ? scheduleCalculator.deadlineFor(routineSchedule, today)
                        : Optional.empty(),
                groupMember.getStatus()
        );
    }

    private Map<LocalDate, Verification> indexVerifications(
            GroupMember groupMember,
            RoutineSchedule routineSchedule,
            List<Verification> verifications,
            Set<LocalDate> scheduledDates,
            LocalDate today
    ) {
        Map<LocalDate, Verification> byDate = new HashMap<>();
        for (Verification verification : verifications) {
            Objects.requireNonNull(verification, "verification must not be null");
            if (!sameMember(groupMember, verification.getGroupMember())
                    || !sameSchedule(routineSchedule, verification.getRoutineSchedule())) {
                throw new IllegalStateException("verification does not belong to the progress target");
            }
            if (!scheduledDates.contains(verification.getScheduledDate())) {
                throw new IllegalStateException(
                        "verification scheduledDate is not a participation-eligible scheduled opportunity"
                );
            }
            if (verification.getStatus() == VerificationStatus.RETRY_REQUIRED) {
                throw new IllegalStateException("RETRY_REQUIRED is not supported by the MVP progress contract");
            }
            if (verification.getScheduledDate().isAfter(today)
                    && verification.getStatus().countsAsProgress()) {
                throw new IllegalStateException("future scheduled opportunity must not be approved");
            }
            if (byDate.putIfAbsent(verification.getScheduledDate(), verification) != null) {
                throw new IllegalStateException("duplicate verification for scheduled opportunity");
            }
        }
        return byDate;
    }

    private OpportunityOutcome outcome(
            RoutineSchedule routineSchedule,
            LocalDate scheduledDate,
            Verification verification,
            LocalDate today,
            Clock clock
    ) {
        VerificationStatus status = verification == null ? null : verification.getStatus();
        if (status == VerificationStatus.REJECTED || status == VerificationStatus.INVALIDATED) {
            return OpportunityOutcome.FAILED;
        }
        if (scheduledDate.isAfter(today)) {
            return OpportunityOutcome.FUTURE;
        }
        if (status != null && status.countsAsProgress()) {
            return OpportunityOutcome.SUCCESS;
        }
        if (isPendingDecision(status)) {
            return OpportunityOutcome.PENDING_DECISION;
        }
        Instant deadline = scheduleCalculator.deadlineFor(routineSchedule, scheduledDate).orElseThrow();
        if (!scheduleCalculator.isDeadlinePassed(deadline, clock)) {
            return OpportunityOutcome.OPEN;
        }
        return OpportunityOutcome.FAILED;
    }

    private boolean isPendingDecision(VerificationStatus status) {
        return status == VerificationStatus.SUBMITTED
                || status == VerificationStatus.PROCESSING
                || status == VerificationStatus.REVIEW_REQUIRED;
    }

    private boolean sameGroup(RoutineGroup first, RoutineGroup second) {
        if (first == second) {
            return true;
        }
        return first.getId() != null && first.getId().equals(second.getId());
    }

    private boolean sameMember(GroupMember first, GroupMember second) {
        if (first == second) {
            return true;
        }
        return first.getId() != null && first.getId().equals(second.getId());
    }

    private boolean sameSchedule(RoutineSchedule first, RoutineSchedule second) {
        if (first == second) {
            return true;
        }
        return first.getId() != null && first.getId().equals(second.getId());
    }

    private enum OpportunityOutcome {
        SUCCESS,
        OPEN,
        PENDING_DECISION,
        FAILED,
        FUTURE
    }
}

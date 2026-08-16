package com.allog.group.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.progress.domain.ParticipationCompletionEvaluation;
import com.allog.progress.domain.PersonalProgressFacts;
import com.allog.progress.service.ParticipationCompletionEvaluator;
import com.allog.progress.service.PersonalProgressCalculator;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.repository.RoutineScheduleRepository;
import com.allog.verification.domain.Verification;
import com.allog.verification.repository.VerificationRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes down how a finished run turned out.
 *
 * <p>It decides nothing itself. Whether a member reached the goal is
 * {@link PersonalProgressCalculator}'s answer and whether the run is over is
 * {@link ParticipationCompletionEvaluator}'s, exactly as the read APIs already report them - so a
 * member's screen and their stored result cannot disagree.
 *
 * <p>Either the whole group is finalised or none of it is. A half-finalised group would leave some
 * members with a verdict and others still running the same finished schedule.
 */
@Service
public class GroupFinalizationService {

    private final GroupMemberRepository groupMemberRepository;
    private final RoutineScheduleRepository routineScheduleRepository;
    private final VerificationRepository verificationRepository;
    private final PersonalProgressCalculator progressCalculator = new PersonalProgressCalculator();
    private final ParticipationCompletionEvaluator completionEvaluator = new ParticipationCompletionEvaluator();

    public GroupFinalizationService(
            GroupMemberRepository groupMemberRepository,
            RoutineScheduleRepository routineScheduleRepository,
            VerificationRepository verificationRepository
    ) {
        this.groupMemberRepository = Objects.requireNonNull(groupMemberRepository);
        this.routineScheduleRepository = Objects.requireNonNull(routineScheduleRepository);
        this.verificationRepository = Objects.requireNonNull(verificationRepository);
    }

    /**
     * Finalises a running group whose row the caller already locked, if it is ready.
     *
     * @return true when the group was completed, false when it is not finished yet - the schedule is
     *         still running, or a verification is still waiting on the AI or an operator.
     */
    public boolean finalizeIfReady(RoutineGroup lockedGroup, Clock snapshotClock) {
        Objects.requireNonNull(lockedGroup, "lockedGroup must not be null");
        Objects.requireNonNull(snapshotClock, "snapshotClock must not be null");

        Long groupId = lockedGroup.getId();
        // The official participants are the ones who actually started, whatever their status is now.
        List<GroupMember> participants = groupMemberRepository
                .findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(groupId);
        if (participants.isEmpty()) {
            throw new IllegalStateException("active routine group has no official participants: " + groupId);
        }

        RoutineSchedule schedule = routineScheduleRepository.findByRoutineGroup_Id(groupId)
                .orElseThrow(() -> new IllegalStateException("routine schedule not found for group: " + groupId));
        Map<Long, List<Verification>> verificationsByMember = loadVerifications(schedule.getId(), participants);

        List<Decision> decisions = new ArrayList<>(participants.size());
        for (GroupMember participant : participants) {
            if (participant.getStatus() != GroupMemberStatus.ACTIVE) {
                // A participant who started but is no longer ACTIVE has no defined outcome yet, and
                // guessing one would invent a policy. Surface it instead.
                throw new IllegalStateException(
                        "official participant is not ACTIVE before finalization: " + participant.getId());
            }
            PersonalProgressFacts facts = progressCalculator.calculate(
                    participant,
                    schedule,
                    verificationsByMember.getOrDefault(participant.getId(), List.of()),
                    snapshotClock
            );
            ParticipationCompletionEvaluation evaluation = completionEvaluator.evaluate(
                    facts, schedule, snapshotClock);
            if (!evaluation.finalizationReady()) {
                // Not over, or something is still pending. Nothing is written; the next pass looks again.
                return false;
            }
            decisions.add(new Decision(participant, evaluation.recommendedOutcome().orElseThrow()));
        }

        // Every outcome is known before anything is written, so no participant is finalised while
        // another one still could not be.
        for (Decision decision : decisions) {
            if (decision.outcome() == ParticipationCompletionEvaluation.Outcome.COMPLETED) {
                decision.member().completeParticipation();
            } else {
                decision.member().failParticipation();
            }
        }
        lockedGroup.complete();
        return true;
    }

    private Map<Long, List<Verification>> loadVerifications(Long scheduleId, List<GroupMember> participants) {
        Map<Long, GroupMember> byId = new LinkedHashMap<>();
        for (GroupMember participant : participants) {
            byId.put(participant.getId(), participant);
        }
        List<Verification> verifications = verificationRepository.findAllForProgressBatch(scheduleId, byId.keySet());
        Map<Long, List<Verification>> grouped = new HashMap<>();
        for (Verification verification : verifications) {
            Long memberId = verification.getGroupMember().getId();
            if (!byId.containsKey(memberId)) {
                throw new IllegalStateException("verification belongs to a non-participant: " + memberId);
            }
            grouped.computeIfAbsent(memberId, key -> new ArrayList<>()).add(verification);
        }
        return grouped;
    }

    private record Decision(GroupMember member, ParticipationCompletionEvaluation.Outcome outcome) {
    }
}

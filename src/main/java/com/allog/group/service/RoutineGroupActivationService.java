package com.allog.group.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.group.repository.RoutineGroupRepository;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.repository.RoutineScheduleRepository;
import com.allog.routine.schedule.RoutineScheduleCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Starts a group once the creator's capacity is met.
 *
 * <p>Two ways in. {@link #activate(Long, Clock)} takes the group lock itself; {@link
 * #activateLocked(RoutineGroup, Instant)} is for callers already holding it - the join that filled
 * the last slot, the creation of a solo group, the reconciler - so a start never queues behind a
 * lock the same transaction already owns.
 */
@Service
public class RoutineGroupActivationService {

    private final RoutineGroupRepository routineGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final RoutineScheduleRepository routineScheduleRepository;
    private final RoutineScheduleCalculator scheduleCalculator = new RoutineScheduleCalculator();

    public RoutineGroupActivationService(
            RoutineGroupRepository routineGroupRepository,
            GroupMemberRepository groupMemberRepository,
            RoutineScheduleRepository routineScheduleRepository
    ) {
        this.routineGroupRepository = Objects.requireNonNull(
                routineGroupRepository,
                "routineGroupRepository must not be null"
        );
        this.groupMemberRepository = Objects.requireNonNull(
                groupMemberRepository,
                "groupMemberRepository must not be null"
        );
        this.routineScheduleRepository = Objects.requireNonNull(
                routineScheduleRepository,
                "routineScheduleRepository must not be null"
        );
    }

    @Transactional
    public void activate(Long groupId, Clock clock) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(clock, "clock must not be null");

        RoutineGroup group = routineGroupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new IllegalArgumentException("routine group not found: " + groupId));
        activateLocked(group, clock.instant());
    }

    /**
     * Starts a group whose row the caller already locked. Every participant is given the same instant,
     * so the run they are measured against begins at one moment rather than per-row.
     */
    public void activateLocked(RoutineGroup lockedGroup, Instant activationTime) {
        Objects.requireNonNull(lockedGroup, "lockedGroup must not be null");
        Objects.requireNonNull(activationTime, "activationTime must not be null");
        if (!lockedGroup.canActivate()) {
            throw new IllegalStateException("routine group cannot activate from " + lockedGroup.getStatus());
        }

        Long groupId = lockedGroup.getId();
        List<GroupMember> members = groupMemberRepository.findAllByRoutineGroup_Id(groupId);
        validatePreActivationState(members);
        List<GroupMember> participants = members.stream()
                .filter(member -> member.getStatus() == GroupMemberStatus.JOINED)
                .toList();
        if (participants.size() != lockedGroup.getMaxMembers()) {
            throw new IllegalStateException(
                    "routine group must be at capacity to activate: " + groupId
            );
        }
        if (!hasEnoughRemainingOpportunities(lockedGroup, activationTime)) {
            throw new IllegalStateException(
                    "requiredCompletionCount must not exceed participation-eligible opportunity count"
            );
        }

        participants.forEach(member -> member.startParticipation(activationTime));
        lockedGroup.activate();
    }

    /**
     * Whether a group starting at this instant could still reach its goal. Read-only, so the
     * reconciler can ask before deciding between starting a group and expiring it.
     */
    public boolean hasEnoughRemainingOpportunities(RoutineGroup group, Instant at) {
        Objects.requireNonNull(group, "group must not be null");
        Objects.requireNonNull(at, "at must not be null");

        RoutineSchedule schedule = requireSchedule(group.getId());
        int eligibleOpportunityCount = scheduleCalculator
                .participationEligibleScheduledDates(schedule, at)
                .size();
        return group.getRequiredCompletionCount() <= eligibleOpportunityCount;
    }

    private RoutineSchedule requireSchedule(Long groupId) {
        return routineScheduleRepository.findByRoutineGroup_Id(groupId)
                .orElseThrow(() -> new IllegalStateException("routine schedule not found for group: " + groupId));
    }

    private void validatePreActivationState(List<GroupMember> members) {
        for (GroupMember member : members) {
            GroupMemberStatus status = member.getStatus();
            if (member.getParticipationStartedAt() != null
                    || status == GroupMemberStatus.ACTIVE
                    || status == GroupMemberStatus.COMPLETED
                    || status == GroupMemberStatus.FAILED) {
                throw new IllegalStateException("member state is inconsistent before group activation: " + member.getId());
            }
        }
    }
}

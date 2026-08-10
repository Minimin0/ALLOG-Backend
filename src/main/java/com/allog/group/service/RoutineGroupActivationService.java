package com.allog.group.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.group.repository.RoutineGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class RoutineGroupActivationService {

    private final RoutineGroupRepository routineGroupRepository;
    private final GroupMemberRepository groupMemberRepository;

    public RoutineGroupActivationService(
            RoutineGroupRepository routineGroupRepository,
            GroupMemberRepository groupMemberRepository
    ) {
        this.routineGroupRepository = Objects.requireNonNull(
                routineGroupRepository,
                "routineGroupRepository must not be null"
        );
        this.groupMemberRepository = Objects.requireNonNull(
                groupMemberRepository,
                "groupMemberRepository must not be null"
        );
    }

    @Transactional
    public void activate(Long groupId, Clock clock) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(clock, "clock must not be null");

        RoutineGroup group = routineGroupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new IllegalArgumentException("routine group not found: " + groupId));
        if (!group.canActivate()) {
            throw new IllegalStateException("routine group cannot activate from " + group.getStatus());
        }

        List<GroupMember> members = groupMemberRepository.findAllByRoutineGroup_Id(groupId);
        validatePreActivationState(members);
        List<GroupMember> participants = members.stream()
                .filter(member -> member.getStatus() == GroupMemberStatus.JOINED)
                .toList();
        if (participants.isEmpty()) {
            throw new IllegalStateException("routine group requires at least one JOINED member to activate");
        }

        LocalDateTime activationTime = LocalDateTime.now(clock);
        participants.forEach(member -> member.startParticipation(activationTime));
        group.activate();
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

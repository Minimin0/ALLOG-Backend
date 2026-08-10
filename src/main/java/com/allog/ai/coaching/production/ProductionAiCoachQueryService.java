package com.allog.ai.coaching.production;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.progress.domain.GroupProgressFacts;
import com.allog.progress.domain.PersonalProgressFacts;
import com.allog.progress.service.GroupProgressCalculator;
import com.allog.progress.service.PersonalProgressCalculator;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.repository.RoutineScheduleRepository;
import com.allog.verification.domain.Verification;
import com.allog.verification.repository.VerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ProductionAiCoachQueryService {

    private final GroupMemberRepository groupMemberRepository;
    private final RoutineScheduleRepository routineScheduleRepository;
    private final VerificationRepository verificationRepository;
    private final Clock clock;
    private final PersonalProgressCalculator personalProgressCalculator = new PersonalProgressCalculator();
    private final GroupProgressCalculator groupProgressCalculator = new GroupProgressCalculator();

    public ProductionAiCoachQueryService(
            GroupMemberRepository groupMemberRepository,
            RoutineScheduleRepository routineScheduleRepository,
            VerificationRepository verificationRepository,
            Clock clock
    ) {
        this.groupMemberRepository = Objects.requireNonNull(
                groupMemberRepository,
                "groupMemberRepository must not be null"
        );
        this.routineScheduleRepository = Objects.requireNonNull(
                routineScheduleRepository,
                "routineScheduleRepository must not be null"
        );
        this.verificationRepository = Objects.requireNonNull(
                verificationRepository,
                "verificationRepository must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(readOnly = true)
    public ProductionAiCoachFacts load(Long groupId, Long currentUserId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(currentUserId, "currentUserId must not be null");

        GroupMember target = groupMemberRepository
                .findByRoutineGroup_IdAndUser_Id(groupId, currentUserId)
                .orElseThrow(() -> new AiCoachParticipationNotFoundException(groupId, currentUserId));
        RoutineGroup group = target.getRoutineGroup();
        GroupMemberStatus status = target.getStatus();
        if (status == GroupMemberStatus.JOINED && target.getParticipationStartedAt() != null) {
            throw new IllegalStateException("JOINED member must not have participationStartedAt");
        }
        if (status != GroupMemberStatus.ACTIVE) {
            return ProductionAiCoachFacts.lifecycle(group.getName(), status);
        }
        if (target.getParticipationStartedAt() == null) {
            throw new IllegalStateException("ACTIVE member must have participationStartedAt");
        }

        RoutineSchedule schedule = routineScheduleRepository.findByRoutineGroup_Id(groupId)
                .orElseThrow(() -> new IllegalStateException("routine schedule not found for group: " + groupId));
        List<GroupMember> eligibleMembers = groupMemberRepository
                .findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(groupId);
        Map<Long, GroupMember> eligibleById = indexEligibleMembers(eligibleMembers);
        if (!eligibleById.containsKey(target.getId())) {
            throw new IllegalStateException("ACTIVE target is missing from official participants");
        }

        List<Verification> verifications = verificationRepository.findAllForProgressBatch(
                schedule.getId(),
                eligibleById.keySet()
        );
        Map<Long, List<Verification>> verificationsByMemberId = groupVerifications(
                verifications,
                eligibleById
        );

        List<PersonalProgressFacts> personalFacts = new ArrayList<>(eligibleMembers.size());
        PersonalProgressFacts targetFacts = null;
        for (GroupMember member : eligibleById.values()) {
            PersonalProgressFacts facts = personalProgressCalculator.calculate(
                    member,
                    schedule,
                    verificationsByMemberId.getOrDefault(member.getId(), List.of()),
                    clock
            );
            personalFacts.add(facts);
            if (member.getId().equals(target.getId())) {
                targetFacts = facts;
            }
        }
        if (targetFacts == null) {
            throw new IllegalStateException("target progress facts were not calculated");
        }
        GroupProgressFacts groupFacts = groupProgressCalculator.calculate(personalFacts);
        return ProductionAiCoachFacts.active(group.getName(), targetFacts, groupFacts);
    }

    private Map<Long, GroupMember> indexEligibleMembers(List<GroupMember> members) {
        Map<Long, GroupMember> byId = new LinkedHashMap<>();
        for (GroupMember member : members) {
            Objects.requireNonNull(member, "eligible member must not be null");
            Long memberId = Objects.requireNonNull(member.getId(), "eligible member id must not be null");
            if (byId.putIfAbsent(memberId, member) != null) {
                throw new IllegalStateException("duplicate eligible member: " + memberId);
            }
        }
        return byId;
    }

    private Map<Long, List<Verification>> groupVerifications(
            List<Verification> verifications,
            Map<Long, GroupMember> eligibleById
    ) {
        Map<Long, List<Verification>> byMemberId = new HashMap<>();
        for (Verification verification : verifications) {
            Objects.requireNonNull(verification, "verification must not be null");
            Long memberId = verification.getGroupMember().getId();
            if (!eligibleById.containsKey(memberId)) {
                throw new IllegalStateException("verification belongs to an ineligible member: " + memberId);
            }
            byMemberId.computeIfAbsent(memberId, ignored -> new ArrayList<>()).add(verification);
        }
        return byMemberId;
    }
}

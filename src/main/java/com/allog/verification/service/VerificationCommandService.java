package com.allog.verification.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.repository.RoutineScheduleRepository;
import com.allog.routine.schedule.RoutineScheduleCalculator;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.repository.VerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

@Service
public class VerificationCommandService {

    private final GroupMemberRepository groupMemberRepository;
    private final RoutineScheduleRepository routineScheduleRepository;
    private final VerificationRepository verificationRepository;
    private final VerificationCreator verificationCreator;
    private final Clock clock;
    private final RoutineScheduleCalculator scheduleCalculator = new RoutineScheduleCalculator();

    public VerificationCommandService(
            GroupMemberRepository groupMemberRepository,
            RoutineScheduleRepository routineScheduleRepository,
            VerificationRepository verificationRepository,
            VerificationCreator verificationCreator,
            Clock clock
    ) {
        this.groupMemberRepository = Objects.requireNonNull(groupMemberRepository);
        this.routineScheduleRepository = Objects.requireNonNull(routineScheduleRepository);
        this.verificationRepository = Objects.requireNonNull(verificationRepository);
        this.verificationCreator = Objects.requireNonNull(verificationCreator);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public Verification createOrGetCurrent(Long groupId, Long currentUserId) {
        GroupMember member = activeMember(groupId, currentUserId);
        RoutineSchedule schedule = schedule(groupId);
        Instant snapshotNow = clock.instant();
        LocalDate currentDate = currentDate(schedule, snapshotNow);
        Instant deadline = eligibleDeadline(member, schedule, currentDate);

        return verificationRepository
                .findByGroupMember_IdAndRoutineSchedule_IdAndScheduledDate(
                        member.getId(), schedule.getId(), currentDate
                )
                .orElseGet(() -> {
                    requireBeforeDeadline(snapshotNow, deadline);
                    return verificationCreator.create(member, schedule, currentDate);
                });
    }

    @Transactional
    public Verification submitCurrent(Long groupId, Long currentUserId) {
        GroupMember member = activeMember(groupId, currentUserId);
        RoutineSchedule schedule = schedule(groupId);
        Instant snapshotNow = clock.instant();
        LocalDate currentDate = currentDate(schedule, snapshotNow);
        Instant deadline = eligibleDeadline(member, schedule, currentDate);
        Verification verification = verificationRepository.findCurrentForUpdate(
                        member.getId(), schedule.getId(), currentDate
                )
                .orElseThrow(() -> new VerificationCommandConflictException(
                        "current verification upload slot does not exist"
                ));

        if (verification.getStatus() == VerificationStatus.PENDING_UPLOAD) {
            if (snapshotNow.isBefore(member.getParticipationStartedAt())) {
                throw new VerificationCommandConflictException("participation has not started");
            }
            requireBeforeDeadline(snapshotNow, deadline);
            verification.submit(Clock.fixed(snapshotNow, clock.getZone()));
            return verification;
        }
        if (verification.getStatus() == VerificationStatus.RETRY_REQUIRED) {
            throw new VerificationCommandConflictException("user retry is not enabled");
        }
        return verification;
    }

    private GroupMember activeMember(Long groupId, Long currentUserId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(currentUserId, "currentUserId must not be null");
        GroupMember member = groupMemberRepository
                .findByRoutineGroup_IdAndUser_IdForUpdate(groupId, currentUserId)
                .orElseThrow(VerificationMembershipNotFoundException::new);
        GroupMemberStatus status = member.getStatus();
        if (status == GroupMemberStatus.LEFT || status == GroupMemberStatus.REMOVED) {
            throw new VerificationMembershipNotFoundException();
        }
        if (status != GroupMemberStatus.ACTIVE) {
            throw new VerificationCommandConflictException("only ACTIVE members can verify");
        }
        if (member.getRoutineGroup().getStatus() != RoutineGroupStatus.ACTIVE) {
            throw new IllegalStateException("ACTIVE member requires an ACTIVE group");
        }
        if (member.getParticipationStartedAt() == null) {
            throw new IllegalStateException("ACTIVE member requires participationStartedAt");
        }
        return member;
    }

    private RoutineSchedule schedule(Long groupId) {
        return routineScheduleRepository.findByRoutineGroup_Id(groupId)
                .orElseThrow(() -> new IllegalStateException("routine schedule not found for group: " + groupId));
    }

    private LocalDate currentDate(RoutineSchedule schedule, Instant snapshotNow) {
        return LocalDate.ofInstant(snapshotNow, ZoneId.of(schedule.getTimezone()));
    }

    private Instant eligibleDeadline(
            GroupMember member,
            RoutineSchedule schedule,
            LocalDate currentDate
    ) {
        Instant deadline = scheduleCalculator.deadlineFor(schedule, currentDate)
                .orElseThrow(() -> new VerificationCommandConflictException(
                        "there is no scheduled verification opportunity today"
                ));
        if (!member.getParticipationStartedAt().isBefore(deadline)) {
            throw new VerificationCommandConflictException(
                    "current opportunity is outside the participation boundary"
            );
        }
        return deadline;
    }

    private void requireBeforeDeadline(Instant snapshotNow, Instant deadline) {
        if (!snapshotNow.isBefore(deadline)) {
            throw new VerificationCommandConflictException("current verification deadline has closed");
        }
    }
}

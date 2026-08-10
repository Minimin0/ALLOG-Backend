package com.allog.verification.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.RoutineGroup;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.schedule.RoutineScheduleCalculator;
import com.allog.verification.domain.Verification;
import com.allog.verification.repository.VerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

@Service
public class VerificationCreator {

    private final VerificationRepository repository;
    private final RoutineScheduleCalculator scheduleCalculator = new RoutineScheduleCalculator();

    public VerificationCreator(VerificationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Transactional
    public Verification create(
            GroupMember groupMember,
            RoutineSchedule routineSchedule,
            LocalDate scheduledDate
    ) {
        Objects.requireNonNull(groupMember, "groupMember must not be null");
        Objects.requireNonNull(routineSchedule, "routineSchedule must not be null");
        Objects.requireNonNull(scheduledDate, "scheduledDate must not be null");
        if (!sameGroup(groupMember.getRoutineGroup(), routineSchedule.getRoutineGroup())) {
            throw new IllegalArgumentException("groupMember and routineSchedule must belong to the same group");
        }
        if (!scheduleCalculator.isScheduledOn(routineSchedule, scheduledDate)) {
            throw new IllegalArgumentException("scheduledDate must be a scheduled opportunity");
        }
        if (repository.existsByGroupMemberAndRoutineScheduleAndScheduledDate(
                groupMember,
                routineSchedule,
                scheduledDate
        )) {
            throw new IllegalStateException("verification already exists for the scheduled opportunity");
        }
        return repository.save(Verification.create(groupMember, routineSchedule, scheduledDate));
    }

    private boolean sameGroup(RoutineGroup first, RoutineGroup second) {
        if (first == second) {
            return true;
        }
        return first.getId() != null && first.getId().equals(second.getId());
    }
}

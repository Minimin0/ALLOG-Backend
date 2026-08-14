package com.allog.progress.service;

import com.allog.group.domain.GroupMember;
import com.allog.progress.domain.PersonalProgressFacts;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.verification.domain.Verification;
import com.allog.verification.repository.VerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

@Service
public class PersonalProgressQueryService {

    private final VerificationRepository verificationRepository;
    private final PersonalProgressCalculator calculator = new PersonalProgressCalculator();

    public PersonalProgressQueryService(VerificationRepository verificationRepository) {
        this.verificationRepository = Objects.requireNonNull(
                verificationRepository,
                "verificationRepository must not be null"
        );
    }

    @Transactional(readOnly = true)
    public PersonalProgressFacts calculate(
            GroupMember groupMember,
            RoutineSchedule routineSchedule,
            Clock clock
    ) {
        Objects.requireNonNull(groupMember, "groupMember must not be null");
        Objects.requireNonNull(routineSchedule, "routineSchedule must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        List<Verification> verifications = verificationRepository
                .findAllByGroupMemberAndRoutineScheduleOrderByScheduledDateAsc(
                        groupMember,
                        routineSchedule
                );
        return calculator.calculate(groupMember, routineSchedule, verifications, clock);
    }
}

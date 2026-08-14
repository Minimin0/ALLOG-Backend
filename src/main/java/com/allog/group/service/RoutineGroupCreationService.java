package com.allog.group.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.dto.CreateRoutineGroupRequest;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.group.repository.RoutineGroupRepository;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.repository.RoutineDefinitionRepository;
import com.allog.routine.repository.RoutineScheduleRepository;
import com.allog.user.domain.User;
import com.allog.user.repository.UserRepository;
import com.allog.verification.template.VerificationTemplateCatalog;
import com.allog.verification.template.domain.VerificationTemplateKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * Creates a routine group, its owner membership, and its schedule in one transaction.
 *
 * <p>A group is either verification-bound or record-only. When a template key is supplied, the
 * approved catalog resolves the exact criteria reference pinned by that template, and both halves of
 * the binding are written together. An unknown or malformed key fails the whole creation rather than
 * silently producing a record-only group.
 */
@Service
public class RoutineGroupCreationService {

    private final RoutineGroupRepository routineGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final RoutineScheduleRepository routineScheduleRepository;
    private final RoutineDefinitionRepository routineDefinitionRepository;
    private final UserRepository userRepository;
    private final VerificationTemplateCatalog verificationTemplateCatalog;
    private final Clock clock;

    public RoutineGroupCreationService(
            RoutineGroupRepository routineGroupRepository,
            GroupMemberRepository groupMemberRepository,
            RoutineScheduleRepository routineScheduleRepository,
            RoutineDefinitionRepository routineDefinitionRepository,
            UserRepository userRepository,
            VerificationTemplateCatalog verificationTemplateCatalog,
            Clock clock
    ) {
        this.routineGroupRepository = Objects.requireNonNull(routineGroupRepository);
        this.groupMemberRepository = Objects.requireNonNull(groupMemberRepository);
        this.routineScheduleRepository = Objects.requireNonNull(routineScheduleRepository);
        this.routineDefinitionRepository = Objects.requireNonNull(routineDefinitionRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.verificationTemplateCatalog = Objects.requireNonNull(verificationTemplateCatalog);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public Long create(Long userId, CreateRoutineGroupRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        User creator = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("authenticated user not found: " + userId));
        RoutineDefinition routineDefinition = routineDefinitionRepository.findById(request.routineDefinitionId())
                .orElseThrow(() -> new RoutineDefinitionNotFoundException(request.routineDefinitionId()));

        RoutineGroup group = routineGroupRepository.save(newGroup(request, routineDefinition, creator));
        groupMemberRepository.save(new GroupMember(
                group,
                creator,
                GroupMemberRole.OWNER,
                GroupMemberStatus.JOINED,
                clock.instant()
        ));
        CreateRoutineGroupRequest.Schedule schedule = request.schedule();
        routineScheduleRepository.save(new RoutineSchedule(
                group,
                schedule.scheduleType(),
                schedule.startDate(),
                schedule.endDate(),
                schedule.deadlineTime(),
                schedule.timezone(),
                schedule.specificDays()
        ));
        return group.getId();
    }

    private RoutineGroup newGroup(
            CreateRoutineGroupRequest request,
            RoutineDefinition routineDefinition,
            User creator
    ) {
        if (request.verificationTemplateKey() == null) {
            return new RoutineGroup(
                    routineDefinition,
                    creator,
                    request.name(),
                    request.visibility(),
                    RoutineGroupStatus.RECRUITING,
                    request.maxMembers(),
                    request.requiredCompletionCount()
            );
        }
        // requireTemplate rejects unapproved keys, and the template carries the exact criteria
        // reference. Nothing here selects a latest or highest version.
        return new RoutineGroup(
                routineDefinition,
                creator,
                request.name(),
                request.visibility(),
                RoutineGroupStatus.RECRUITING,
                request.maxMembers(),
                request.requiredCompletionCount(),
                verificationTemplateCatalog.requireTemplate(
                        new VerificationTemplateKey(request.verificationTemplateKey())
                )
        );
    }
}

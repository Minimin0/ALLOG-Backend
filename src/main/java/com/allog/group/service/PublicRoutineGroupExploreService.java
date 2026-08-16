package com.allog.group.service;

import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.dto.PublicRoutineGroupsResponse;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.group.repository.RoutineGroupRepository;
import com.allog.routine.repository.RoutineScheduleRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicRoutineGroupExploreService {

    private final RoutineGroupRepository routineGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final RoutineScheduleRepository scheduleRepository;

    public PublicRoutineGroupExploreService(
            RoutineGroupRepository routineGroupRepository,
            GroupMemberRepository groupMemberRepository,
            RoutineScheduleRepository scheduleRepository
    ) {
        this.routineGroupRepository = Objects.requireNonNull(routineGroupRepository);
        this.groupMemberRepository = Objects.requireNonNull(groupMemberRepository);
        this.scheduleRepository = Objects.requireNonNull(scheduleRepository);
    }

    @Transactional(readOnly = true)
    public PublicRoutineGroupsResponse explore() {
        return new PublicRoutineGroupsResponse(routineGroupRepository.findPublicRecruiting().stream()
                .map(group -> PublicRoutineGroupsResponse.Item.from(
                        group,
                        groupMemberRepository.countByRoutineGroup_IdAndStatus(group.getId(), GroupMemberStatus.JOINED),
                        scheduleRepository.findByRoutineGroup_Id(group.getId())
                                .orElseThrow(() -> new IllegalStateException("routine group has no schedule: " + group.getId()))
                ))
                .toList());
    }
}

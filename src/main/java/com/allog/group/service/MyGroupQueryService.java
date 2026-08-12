package com.allog.group.service;

import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.dto.MyGroupDetailResponse;
import com.allog.group.dto.MyGroupsResponse;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.routine.repository.RoutineScheduleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class MyGroupQueryService {

    private static final Set<GroupMemberStatus> VISIBLE_STATUSES = Set.of(
            GroupMemberStatus.JOINED,
            GroupMemberStatus.ACTIVE,
            GroupMemberStatus.COMPLETED,
            GroupMemberStatus.FAILED
    );
    private static final Sort FIXED_SORT = Sort.by(
            Sort.Order.desc("joinedAt"),
            Sort.Order.desc("id")
    );

    private final GroupMemberRepository repository;
    private final RoutineScheduleRepository scheduleRepository;

    public MyGroupQueryService(
            GroupMemberRepository repository,
            RoutineScheduleRepository scheduleRepository
    ) {
        this.repository = repository;
        this.scheduleRepository = scheduleRepository;
    }

    @Transactional(readOnly = true)
    public MyGroupsResponse readMyGroups(Long currentUserId, int page, int size) {
        return MyGroupsResponse.from(repository.findAllByUser_IdAndStatusIn(
                currentUserId,
                VISIBLE_STATUSES,
                PageRequest.of(page, size, FIXED_SORT)
        ));
    }

    @Transactional(readOnly = true)
    public MyGroupDetailResponse readMyGroup(Long currentUserId, Long groupId) {
        var membership = repository.findByRoutineGroup_IdAndUser_IdAndStatusIn(
                        groupId,
                        currentUserId,
                        VISIBLE_STATUSES
                )
                .orElseThrow(MyGroupNotFoundException::new);
        return MyGroupDetailResponse.from(
                membership,
                scheduleRepository.findByRoutineGroup_Id(groupId).orElse(null)
        );
    }
}

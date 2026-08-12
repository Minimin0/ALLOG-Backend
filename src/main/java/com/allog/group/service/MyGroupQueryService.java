package com.allog.group.service;

import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.dto.MyGroupsResponse;
import com.allog.group.repository.GroupMemberRepository;
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

    public MyGroupQueryService(GroupMemberRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public MyGroupsResponse readMyGroups(Long currentUserId, int page, int size) {
        return MyGroupsResponse.from(repository.findAllByUser_IdAndStatusIn(
                currentUserId,
                VISIBLE_STATUSES,
                PageRequest.of(page, size, FIXED_SORT)
        ));
    }
}

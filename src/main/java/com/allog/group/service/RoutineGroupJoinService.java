package com.allog.group.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.group.repository.RoutineGroupRepository;
import com.allog.user.domain.User;
import com.allog.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * Adds an authenticated user to a recruiting group.
 *
 * <p>Capacity is a concurrency problem, not an arithmetic one: two users racing for the last slot
 * would both read the same count. The group row is therefore locked first, so joins on one group
 * serialize and the count is re-read behind the lock. {@code uk_group_member_group_user} remains the
 * final defence against a duplicate membership.
 */
@Service
public class RoutineGroupJoinService {

    private final RoutineGroupRepository routineGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public RoutineGroupJoinService(
            RoutineGroupRepository routineGroupRepository,
            GroupMemberRepository groupMemberRepository,
            UserRepository userRepository,
            Clock clock
    ) {
        this.routineGroupRepository = Objects.requireNonNull(routineGroupRepository);
        this.groupMemberRepository = Objects.requireNonNull(groupMemberRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public void join(Long groupId, Long userId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        RoutineGroup group = routineGroupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new RoutineGroupJoinException(
                        RoutineGroupJoinException.Reason.GROUP_NOT_FOUND,
                        "routine group not found: " + groupId
                ));
        if (!group.canAcceptNewMember()) {
            throw new RoutineGroupJoinException(
                    RoutineGroupJoinException.Reason.NOT_JOINABLE,
                    "routine group does not accept new members in " + group.getStatus()
            );
        }
        if (groupMemberRepository.existsByRoutineGroup_IdAndUser_Id(groupId, userId)) {
            throw new RoutineGroupJoinException(
                    RoutineGroupJoinException.Reason.ALREADY_JOINED,
                    "user already has a membership in routine group " + groupId
            );
        }

        // Counted behind the group lock. The owner holds a JOINED membership from creation, so the
        // creator occupies one slot of maxMembers.
        long joined = groupMemberRepository.countByRoutineGroup_IdAndStatus(groupId, GroupMemberStatus.JOINED);
        if (joined >= group.getMaxMembers()) {
            throw new RoutineGroupJoinException(
                    RoutineGroupJoinException.Reason.GROUP_FULL,
                    "routine group has no remaining capacity: " + groupId
            );
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("authenticated user not found: " + userId));
        groupMemberRepository.save(new GroupMember(
                group,
                user,
                GroupMemberRole.MEMBER,
                GroupMemberStatus.JOINED,
                clock.instant()
        ));
        if (joined + 1 == group.getMaxMembers()) {
            group.markFull();
        }
    }
}

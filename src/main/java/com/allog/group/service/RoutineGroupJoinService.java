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
import java.time.Instant;
import java.util.Objects;

/**
 * Adds an authenticated user to a recruiting group.
 *
 * <p>Capacity is a concurrency problem, not an arithmetic one: two users racing for the last slot
 * would both read the same count. The group row is therefore locked first, so joins on one group
 * serialize and the count is re-read behind the lock. {@code uk_group_member_group_user} remains the
 * final defence against a duplicate membership.
 *
 * <p>Filling the last slot also starts the group, in this same transaction and behind this same
 * lock, so capacity and activation can never disagree.
 */
@Service
public class RoutineGroupJoinService {

    private final RoutineGroupRepository routineGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final RoutineGroupActivationService activationService;
    private final Clock clock;

    public RoutineGroupJoinService(
            RoutineGroupRepository routineGroupRepository,
            GroupMemberRepository groupMemberRepository,
            UserRepository userRepository,
            RoutineGroupActivationService activationService,
            Clock clock
    ) {
        this.routineGroupRepository = Objects.requireNonNull(routineGroupRepository);
        this.groupMemberRepository = Objects.requireNonNull(groupMemberRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.activationService = Objects.requireNonNull(activationService);
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

        // One reading of the clock for the whole command, so the join and any activation it triggers
        // agree on when this happened.
        Instant now = clock.instant();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("authenticated user not found: " + userId));
        GroupMember member = groupMemberRepository.saveAndFlush(new GroupMember(
                group,
                user,
                GroupMemberRole.MEMBER,
                GroupMemberStatus.JOINED,
                now
        ));
        if (joined + 1 < group.getMaxMembers()) {
            return;
        }

        // The slot that filled the room is the slot that starts it: waiting for a scheduler would
        // leave members looking at a full room that has not begun.
        group.markFull();
        if (!activationService.hasEnoughRemainingOpportunities(group, now)) {
            // The schedule ran out while this room was recruiting. Refusing here keeps a membership
            // that could never be completed out of the database; the reconciler expires the group.
            throw new RoutineGroupJoinException(
                    RoutineGroupJoinException.Reason.NOT_JOINABLE,
                    "routine group can no longer reach its goal: " + groupId
            );
        }
        activationService.activateLocked(group, now);
        Objects.requireNonNull(member.getId(), "joined member must be persisted before activation");
    }
}

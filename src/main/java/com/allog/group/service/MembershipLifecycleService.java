package com.allog.group.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.group.repository.RoutineGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * The two ways a group can end before it starts: a member walking away, or the owner closing it.
 *
 * <p>Both take the group lock first, the same order joins and activation use, so a departure and a
 * last-slot join can never interleave into a room that is both full and short a member.
 *
 * <p>Neither touches hearts. Recording what happened is this milestone's job; paying anyone back is
 * M3-C's, once these events exist to trigger it.
 */
@Service
public class MembershipLifecycleService {

    private final RoutineGroupRepository routineGroupRepository;
    private final GroupMemberRepository groupMemberRepository;

    public MembershipLifecycleService(
            RoutineGroupRepository routineGroupRepository,
            GroupMemberRepository groupMemberRepository
    ) {
        this.routineGroupRepository = Objects.requireNonNull(routineGroupRepository);
        this.groupMemberRepository = Objects.requireNonNull(groupMemberRepository);
    }

    /**
     * A member leaves a group that has not started.
     *
     * <p>Repeating it is a no-op rather than an error: a retried request from a phone that lost its
     * answer should not read as a failure. The membership row stays behind, which is what stops the
     * same person rejoining a room they left.
     */
    @Transactional
    public void leave(Long groupId, Long userId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        RoutineGroup group = lockGroup(groupId);
        GroupMember member = groupMemberRepository.findByRoutineGroup_IdAndUser_Id(groupId, userId)
                .orElseThrow(() -> new GroupLifecycleException(
                        GroupLifecycleException.Reason.MEMBERSHIP_NOT_FOUND,
                        "no membership in routine group " + groupId));

        if (member.getStatus() == GroupMemberStatus.LEFT) {
            return;
        }
        if (member.getRole() == GroupMemberRole.OWNER) {
            throw new GroupLifecycleException(
                    GroupLifecycleException.Reason.OWNER_MUST_CANCEL,
                    "the owner cancels a routine group instead of leaving it");
        }
        if (!group.isBeforeStart() || member.getStatus() != GroupMemberStatus.JOINED) {
            // Leaving a running group is a product decision nobody has made, so it is refused rather
            // than guessed at.
            throw new GroupLifecycleException(
                    GroupLifecycleException.Reason.NOT_LEAVABLE,
                    "member cannot leave routine group in " + group.getStatus());
        }

        member.leaveBeforeStart();
        if (group.getStatus() == RoutineGroupStatus.FULL) {
            group.reopenRecruitingAfterDeparture();
        }
    }

    /**
     * The owner closes a group that has not started. Everyone still holding a place is removed;
     * anyone who already left keeps that, because it is what actually happened to them.
     */
    @Transactional
    public void cancel(Long groupId, Long userId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        RoutineGroup group = lockGroup(groupId);
        if (!group.getCreatedBy().getId().equals(userId)) {
            // Under /me, a group you do not own is a group you cannot see.
            throw new GroupLifecycleException(
                    GroupLifecycleException.Reason.GROUP_NOT_FOUND,
                    "routine group not found: " + groupId);
        }
        if (group.getStatus() == RoutineGroupStatus.CANCELLED) {
            return;
        }
        if (!group.isBeforeStart()) {
            throw new GroupLifecycleException(
                    GroupLifecycleException.Reason.NOT_CANCELLABLE,
                    "routine group cannot be cancelled from " + group.getStatus());
        }

        removeRemainingMembers(groupId);
        group.cancelBeforeStart();
    }

    /** Shared by cancellation and expiry: both end a group whose members never started. */
    void removeRemainingMembers(Long groupId) {
        List<GroupMember> members = groupMemberRepository.findAllByRoutineGroup_Id(groupId);
        for (GroupMember member : members) {
            if (member.getStatus() == GroupMemberStatus.JOINED) {
                member.removeBeforeStart();
            }
        }
    }

    private RoutineGroup lockGroup(Long groupId) {
        return routineGroupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new GroupLifecycleException(
                        GroupLifecycleException.Reason.GROUP_NOT_FOUND,
                        "routine group not found: " + groupId));
    }
}

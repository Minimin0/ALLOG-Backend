package com.allog.group.service;

import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupInvite;
import com.allog.group.dto.GroupInviteResponse;
import com.allog.group.repository.RoutineGroupInviteRepository;
import com.allog.group.repository.RoutineGroupRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutineGroupInviteService {
    private static final int INVITE_CODE_BYTES = 9;

    private final RoutineGroupRepository routineGroupRepository;
    private final RoutineGroupInviteRepository inviteRepository;
    private final RoutineGroupJoinService routineGroupJoinService;
    private final SecureRandom secureRandom = new SecureRandom();

    public RoutineGroupInviteService(
            RoutineGroupRepository routineGroupRepository,
            RoutineGroupInviteRepository inviteRepository,
            RoutineGroupJoinService routineGroupJoinService
    ) {
        this.routineGroupRepository = Objects.requireNonNull(routineGroupRepository);
        this.inviteRepository = Objects.requireNonNull(inviteRepository);
        this.routineGroupJoinService = Objects.requireNonNull(routineGroupJoinService);
    }

    @Transactional
    public GroupInviteResponse issue(Long groupId, Long ownerUserId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        RoutineGroup group = routineGroupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new GroupInviteException(
                        GroupInviteException.Reason.GROUP_NOT_FOUND,
                        "routine group not found: " + groupId));
        if (!group.getCreatedBy().getId().equals(ownerUserId)) {
            throw new GroupInviteException(
                    GroupInviteException.Reason.GROUP_NOT_FOUND,
                    "routine group not found: " + groupId);
        }
        if (group.getVisibility() != GroupVisibility.PRIVATE) {
            throw new GroupInviteException(
                    GroupInviteException.Reason.NOT_PRIVATE,
                    "routine group is not private: " + groupId);
        }
        return new GroupInviteResponse(inviteRepository.findByRoutineGroup_Id(groupId)
                .map(RoutineGroupInvite::getCode)
                .orElseGet(() -> inviteRepository.saveAndFlush(new RoutineGroupInvite(
                        group, group.getCreatedBy(), newCode())).getCode()));
    }

    @Transactional
    public void joinByCode(String code, Long userId) {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        RoutineGroupInvite invite = inviteRepository.findByCode(code)
                .orElseThrow(() -> new GroupInviteException(
                        GroupInviteException.Reason.INVITE_NOT_FOUND,
                        "routine group invite not found"));
        routineGroupJoinService.joinByInvite(invite.getRoutineGroup().getId(), userId);
    }

    private String newCode() {
        byte[] bytes = new byte[INVITE_CODE_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

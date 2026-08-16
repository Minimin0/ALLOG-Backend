package com.allog.user.service;

import com.allog.heart.service.HeartAccountService;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.reward.repository.VerificationRewardRepository;
import com.allog.user.dto.UserStatsResponse;
import com.allog.user.repository.UserProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Reads the counters a member sees. Hearts come from the wallet, which is the balance authority; the
 * ledger is not summed here, because two ways of computing the same number is how they start to
 * disagree.
 */
@Service
public class UserStatsService {

    private final UserProfileRepository profileRepository;
    private final HeartAccountService heartAccountService;
    private final VerificationRewardRepository rewardRepository;
    private final GroupMemberRepository groupMemberRepository;

    public UserStatsService(
            UserProfileRepository profileRepository,
            HeartAccountService heartAccountService,
            GroupMemberRepository groupMemberRepository,
            VerificationRewardRepository rewardRepository
    ) {
        this.profileRepository = Objects.requireNonNull(profileRepository);
        this.heartAccountService = Objects.requireNonNull(heartAccountService);
        this.groupMemberRepository = Objects.requireNonNull(groupMemberRepository);
        this.rewardRepository = Objects.requireNonNull(rewardRepository);
    }

    @Transactional(readOnly = true)
    public UserStatsResponse read(Long userId) {
        // Stats belong to a member, and a member is a profile. Without one there is nothing to count.
        if (!profileRepository.existsByUser_Id(userId)) {
            throw new ProfileNotFoundException();
        }
        return new UserStatsResponse(
                heartAccountService.balanceOf(userId),
                rewardRepository.sumPointsByUserId(userId),
                groupMemberRepository.countByUser_IdAndStatus(userId, GroupMemberStatus.COMPLETED)
        );
    }
}

package com.allog.user.service;

import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.heart.service.HeartAccountService;
import com.allog.reward.repository.VerificationRewardRepository;
import com.allog.user.dto.UserStatsResponse;
import com.allog.user.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatsServiceTest {
    @Mock
    private UserProfileRepository profileRepository;
    @Mock
    private HeartAccountService heartAccountService;
    @Mock
    private VerificationRewardRepository rewardRepository;
    @Mock
    private GroupMemberRepository groupMemberRepository;
    @InjectMocks
    private UserStatsService statsService;

    @Test
    void readsSuccessfulRoutinesOnlyFromPersistedCompletedMemberships() {
        Long userId = 17L;
        when(profileRepository.existsByUser_Id(userId)).thenReturn(true);
        when(heartAccountService.balanceOf(userId)).thenReturn(2);
        when(rewardRepository.sumPointsByUserId(userId)).thenReturn(40L);
        when(groupMemberRepository.countByUser_IdAndStatus(userId, GroupMemberStatus.COMPLETED)).thenReturn(3L);

        UserStatsResponse response = statsService.read(userId);

        assertEquals(2, response.hearts());
        assertEquals(40L, response.rewardPoints());
        assertEquals(3L, response.successfulRoutines());
    }
}

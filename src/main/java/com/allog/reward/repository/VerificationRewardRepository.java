package com.allog.reward.repository;

import com.allog.reward.domain.VerificationReward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface VerificationRewardRepository extends JpaRepository<VerificationReward, Long> {

    boolean existsByVerification_Id(Long verificationId);

    Optional<VerificationReward> findByVerification_Id(Long verificationId);

    /**
     * Everything this member has earned. A reward points at a verification, not at a user, so the
     * owner is reached through the membership that submitted it.
     *
     * <p>Coalesced because a member with no approvals has no rows, and the sum of nothing is null
     * rather than zero.
     */
    @Query("""
            select coalesce(sum(reward.points), 0)
              from VerificationReward reward
              join reward.verification verification
              join verification.groupMember member
             where member.user.id = :userId
            """)
    long sumPointsByUserId(@Param("userId") Long userId);
}

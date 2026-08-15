package com.allog.reward.repository;

import com.allog.reward.domain.VerificationReward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationRewardRepository extends JpaRepository<VerificationReward, Long> {

    boolean existsByVerification_Id(Long verificationId);

    Optional<VerificationReward> findByVerification_Id(Long verificationId);
}

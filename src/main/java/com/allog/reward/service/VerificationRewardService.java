package com.allog.reward.service;

import com.allog.reward.domain.VerificationReward;
import com.allog.reward.repository.VerificationRewardRepository;
import com.allog.verification.domain.Verification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * Pays out an approved verification.
 *
 * <p>Called directly from the two places an approval happens, and required to run inside their
 * transaction: a reward that commits separately from the approval it pays for can be lost with
 * nothing left to retry it. There is no outbox and no scheduler in this system to pick that up.
 *
 * <p>Granting twice is a no-op rather than an error, so replaying an approval - a retried worker
 * call, an operator clicking twice - cannot pay a member twice.
 */
@Service
public class VerificationRewardService {

    private final VerificationRewardRepository rewardRepository;
    private final Clock clock;

    public VerificationRewardService(VerificationRewardRepository rewardRepository, Clock clock) {
        this.rewardRepository = Objects.requireNonNull(rewardRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void grantFor(Verification verification) {
        Objects.requireNonNull(verification, "verification must not be null");
        if (rewardRepository.existsByVerification_Id(verification.getId())) {
            return;
        }
        rewardRepository.save(VerificationReward.grant(verification, clock));
    }
}

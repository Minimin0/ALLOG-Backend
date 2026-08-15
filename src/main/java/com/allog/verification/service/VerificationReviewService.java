package com.allog.verification.service;

import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.repository.VerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * The manual way out of a hold. Everything the AI could not settle ends up in REVIEW_REQUIRED, and
 * without this a member's verification would sit there forever.
 *
 * <p>Only a held verification can be settled by hand. A SUBMITTED one is deliberately not accepted:
 * its analysis may still be in flight, and approving underneath it would make the analysis fail its
 * own transition and be retried as stale for as long as the worker runs.
 */
@Service
public class VerificationReviewService {

    private final VerificationRepository verificationRepository;
    private final Clock clock;

    public VerificationReviewService(VerificationRepository verificationRepository, Clock clock) {
        this.verificationRepository = Objects.requireNonNull(verificationRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public Verification approve(Long verificationId) {
        Verification verification = heldForReview(verificationId);
        verification.approve(clock);
        return verification;
    }

    @Transactional
    public Verification reject(Long verificationId, String reviewNote) {
        Verification verification = heldForReview(verificationId);
        verification.reject(reviewNote);
        return verification;
    }

    private Verification heldForReview(Long verificationId) {
        Objects.requireNonNull(verificationId, "verificationId must not be null");
        Verification verification = verificationRepository.findByIdForUpdate(verificationId)
                .orElseThrow(() -> new VerificationNotFoundException(verificationId));
        VerificationStatus status = verification.getStatus();
        if (status != VerificationStatus.REVIEW_REQUIRED && status != VerificationStatus.PROCESSING) {
            throw new VerificationCommandConflictException(
                    "only a verification held for review can be settled by hand"
            );
        }
        return verification;
    }
}

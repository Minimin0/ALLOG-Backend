package com.allog.verification.service;

import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.repository.VerificationRepository;
import com.allog.verification.storage.VerificationMediaStorage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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

    private static final int QUEUE_LIMIT = 100;

    private final VerificationRepository verificationRepository;
    private final VerificationMediaStorage storage;
    private final VerificationMediaPolicy mediaPolicy;
    private final Clock clock;

    public VerificationReviewService(
            VerificationRepository verificationRepository,
            VerificationMediaStorage storage,
            VerificationMediaPolicy mediaPolicy,
            Clock clock
    ) {
        this.verificationRepository = Objects.requireNonNull(verificationRepository);
        this.storage = Objects.requireNonNull(storage);
        this.mediaPolicy = Objects.requireNonNull(mediaPolicy);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Everything currently waiting on a person, oldest first. Capped rather than paged: an operator
     * works the front of the queue, and an unbounded admin query is a production hazard on its own.
     */
    @Transactional(readOnly = true)
    public List<PendingReview> reviewQueue() {
        Instant linkExpiry = clock.instant().plus(mediaPolicy.uploadExpiry());
        return verificationRepository
                .findReviewQueue(VerificationStatus.REVIEW_REQUIRED, PageRequest.of(0, QUEUE_LIMIT))
                .stream()
                .map(row -> PendingReview.from(row, mediaLink(row.mediaObjectKey(), linkExpiry)))
                .toList();
    }

    private URI mediaLink(String objectKey, Instant expiresAt) {
        return objectKey == null ? null : storage.issueDownload(objectKey, expiresAt);
    }

    @Transactional
    public Verification approve(Long verificationId, Long operatorId) {
        Verification verification = heldForReview(verificationId);
        verification.approveByOperator(clock, operatorId);
        return verification;
    }

    @Transactional
    public Verification reject(Long verificationId, Long operatorId, String reviewNote) {
        Verification verification = heldForReview(verificationId);
        verification.rejectByOperator(clock, operatorId, reviewNote);
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

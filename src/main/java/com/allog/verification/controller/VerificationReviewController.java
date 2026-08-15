package com.allog.verification.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.verification.analysis.controller.VerificationAnalysisOperationsProperties;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.service.PendingReview;
import com.allog.verification.service.VerificationReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Objects;

/**
 * Settles verifications the AI put on hold. Reuses the operator allowlist that gates the analysis
 * worker controls, so there is still exactly one place that says who may act as an operator.
 */
@RestController
@RequestMapping("/api/v1/admin/verifications")
public class VerificationReviewController {

    private final VerificationReviewService reviewService;
    private final VerificationAnalysisOperationsProperties properties;

    public VerificationReviewController(
            VerificationReviewService reviewService,
            VerificationAnalysisOperationsProperties properties
    ) {
        this.reviewService = Objects.requireNonNull(reviewService);
        this.properties = Objects.requireNonNull(properties);
    }

    /** The work queue: everything an operator still has to settle, oldest first. */
    @GetMapping("/pending-review")
    public List<PendingReview> pendingReview(@AuthenticationPrincipal AllogPrincipal principal) {
        requireOperator(principal);
        return reviewService.reviewQueue();
    }

    @PostMapping("/{verificationId}/approve")
    public ReviewResponse approve(
            @Positive @PathVariable Long verificationId,
            @AuthenticationPrincipal AllogPrincipal principal
    ) {
        requireOperator(principal);
        return ReviewResponse.from(reviewService.approve(verificationId));
    }

    @PostMapping("/{verificationId}/reject")
    public ReviewResponse reject(
            @Positive @PathVariable Long verificationId,
            @AuthenticationPrincipal AllogPrincipal principal,
            @Valid @RequestBody RejectRequest request
    ) {
        requireOperator(principal);
        return ReviewResponse.from(reviewService.reject(verificationId, request.reason()));
    }

    private void requireOperator(AllogPrincipal principal) {
        if (principal == null || !properties.isOperator(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record ReviewResponse(Long verificationId, VerificationStatus status, String reviewNote) {

        static ReviewResponse from(Verification verification) {
            return new ReviewResponse(
                    verification.getId(),
                    verification.getStatus(),
                    verification.getReviewNote()
            );
        }
    }
}

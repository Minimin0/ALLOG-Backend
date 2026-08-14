package com.allog.verification.service;

import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record VerificationSubmissionResult(
        Long verificationId,
        LocalDate scheduledDate,
        VerificationStatus status,
        Instant submittedAt
) {

    static VerificationSubmissionResult from(Verification verification) {
        Objects.requireNonNull(verification, "verification must not be null");
        if (verification.getSubmittedAt() == null) {
            throw new IllegalStateException("accepted verification requires submittedAt");
        }
        return new VerificationSubmissionResult(
                verification.getId(),
                verification.getScheduledDate(),
                verification.getStatus(),
                verification.getSubmittedAt()
        );
    }
}

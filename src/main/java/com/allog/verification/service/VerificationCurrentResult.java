package com.allog.verification.service;

import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record VerificationCurrentResult(
        Long verificationId,
        LocalDate scheduledDate,
        VerificationStatus status,
        Instant submissionDeadline
) {

    static VerificationCurrentResult from(Verification verification, Instant submissionDeadline) {
        Objects.requireNonNull(verification, "verification must not be null");
        return new VerificationCurrentResult(
                verification.getId(),
                verification.getScheduledDate(),
                verification.getStatus(),
                Objects.requireNonNull(submissionDeadline, "submissionDeadline must not be null")
        );
    }
}

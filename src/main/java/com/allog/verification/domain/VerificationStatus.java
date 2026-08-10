package com.allog.verification.domain;

public enum VerificationStatus {
    PENDING_UPLOAD,
    SUBMITTED,
    PROCESSING,
    APPROVED,
    REVIEW_REQUIRED,
    RETRY_REQUIRED,
    REJECTED,
    INVALIDATED;

    public boolean countsAsProgress() {
        return this == APPROVED;
    }
}

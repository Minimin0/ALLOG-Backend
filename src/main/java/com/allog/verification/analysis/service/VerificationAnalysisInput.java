package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationCriteria;

import java.util.Objects;
import java.util.UUID;

public record VerificationAnalysisInput(
        Long analysisId,
        UUID analysisRequestId,
        int attemptCount,
        VerificationCriteria.Reference criteriaReference,
        Long verificationId,
        String objectKey,
        String contentType,
        long sizeBytes
) {

    public VerificationAnalysisInput {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(analysisRequestId, "analysisRequestId must not be null");
        Objects.requireNonNull(criteriaReference, "criteriaReference must not be null");
        Objects.requireNonNull(verificationId, "verificationId must not be null");
        objectKey = requireText(objectKey, "objectKey");
        contentType = requireText(contentType, "contentType");
        if (attemptCount <= 0) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysis;

import java.util.Objects;
import java.util.UUID;

public record VerificationAnalysisClaim(
        Long analysisId,
        UUID analysisRequestId,
        int attemptCount
) {

    public VerificationAnalysisClaim {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(analysisRequestId, "analysisRequestId must not be null");
        if (attemptCount <= 0) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
    }

    static VerificationAnalysisClaim from(VerificationAnalysis analysis) {
        return new VerificationAnalysisClaim(
                analysis.getId(),
                analysis.getAnalysisRequestId(),
                analysis.getAttemptCount()
        );
    }
}

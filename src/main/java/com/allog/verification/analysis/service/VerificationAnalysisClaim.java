package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysis;

import java.util.UUID;

public record VerificationAnalysisClaim(
        Long analysisId,
        UUID analysisRequestId,
        int attemptCount
) {

    static VerificationAnalysisClaim from(VerificationAnalysis analysis) {
        return new VerificationAnalysisClaim(
                analysis.getId(),
                analysis.getAnalysisRequestId(),
                analysis.getAttemptCount()
        );
    }
}

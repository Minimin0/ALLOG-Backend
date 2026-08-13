package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.AnalysisRecommendation;

import java.math.BigDecimal;
import java.util.Objects;

public record VerificationAnalysisSuccessResult(
        AnalysisRecommendation recommendation,
        String reasonCode,
        String providerModel,
        String criteriaVersion,
        Boolean objectPresence,
        BigDecimal relevanceScore,
        Boolean anomalyDetected,
        Boolean framedProperly
) {

    public VerificationAnalysisSuccessResult {
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        if (relevanceScore != null
                && (relevanceScore.signum() < 0 || relevanceScore.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("relevanceScore must be between 0 and 1");
        }
    }
}

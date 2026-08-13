package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationCriteria;

import java.util.Objects;

public record VerificationAnalysisSuccessResult(
        AnalysisRecommendation recommendation,
        VerificationCriteria.Reference criteriaReference,
        VerificationAnalysisProvider.Result providerResult
) {

    public VerificationAnalysisSuccessResult {
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        Objects.requireNonNull(criteriaReference, "criteriaReference must not be null");
        Objects.requireNonNull(providerResult, "providerResult must not be null");
    }
}

package com.allog.verification.analysis.evaluation;

import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.template.domain.VerificationTemplateKey;

import java.util.Objects;

/**
 * One versioned evaluation case: a media asset plus the human ground truth for it.
 *
 * <p>Carries no user, group, verification, analysis, or attempt identity. Evaluation compares a
 * criteria-scoped observation against a human label and needs nothing else, so nothing else is
 * modelled here.
 *
 * <p>No expected numeric score is recorded. Humans label meaning, not calibration values; the
 * numeric side is explored by sweeping thresholds against these labels.
 */
public record EvaluationCase(
        String caseId,
        VerificationTemplateKey templateKey,
        VerificationCriteria.Reference criteriaReference,
        String assetRef,
        EvaluationHumanLabel humanLabel
) {

    public EvaluationCase {
        caseId = requireIdentifier(caseId, "caseId");
        Objects.requireNonNull(templateKey, "templateKey must not be null");
        Objects.requireNonNull(criteriaReference, "criteriaReference must not be null");
        assetRef = requireAssetRef(assetRef);
        Objects.requireNonNull(humanLabel, "humanLabel must not be null");
    }

    private static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (!trimmed.equals(value)) {
            throw new IllegalArgumentException(name + " must not have surrounding whitespace");
        }
        return trimmed;
    }

    /**
     * Asset references stay flat filenames inside the dataset directory so that a future loader
     * cannot be steered outside it by an edited manifest.
     */
    private static String requireAssetRef(String value) {
        String assetRef = requireIdentifier(value, "assetRef");
        if (assetRef.contains("/") || assetRef.contains("\\") || assetRef.contains("..")) {
            throw new IllegalArgumentException("assetRef must be a flat filename within the dataset directory");
        }
        return assetRef;
    }
}

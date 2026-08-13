package com.allog.verification.analysis.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Provider observations only. These values are evidence measurements, not a pass/reject decision.
 * Nullable measurements mean the provider could not assess that observation.
 */
public record VerificationAnalysisObservation(
        Boolean objectPresence,
        BigDecimal relevanceScore,
        Boolean anomalyDetected,
        Boolean framedProperly,
        ReasonCode reasonCode
) {

    public VerificationAnalysisObservation {
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        if (relevanceScore != null
                && (relevanceScore.signum() < 0 || relevanceScore.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("relevanceScore must be between 0 and 1");
        }
        if (reasonCode == ReasonCode.POTENTIAL_INTEGRITY_ANOMALY && !Boolean.TRUE.equals(anomalyDetected)) {
            throw new IllegalArgumentException("potential integrity anomaly requires anomalyDetected=true");
        }
    }

    public boolean hasValue(VerificationCriteria.ObservationType type) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case TARGET_EVIDENCE_VISIBLE -> objectPresence != null;
            case CRITERIA_RELEVANCE_SCORE -> relevanceScore != null;
            case INTEGRITY_ANOMALY -> anomalyDetected != null;
            case FRAMING_SUFFICIENCY -> framedProperly != null;
        };
    }

    public enum ReasonCode {
        OBSERVATION_COMPLETE,
        OBSERVATION_PARTIAL,
        OBSERVATION_INSUFFICIENT,
        POTENTIAL_INTEGRITY_ANOMALY
    }
}

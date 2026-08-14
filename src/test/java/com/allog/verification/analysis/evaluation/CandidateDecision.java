package com.allog.verification.analysis.evaluation;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Evaluation-only candidate rule under test. This is NOT the Backend DecisionPolicy, and nothing
 * here may move into {@code src/main}: production still has no decision policy precisely because
 * the evidence to choose one does not exist yet.
 *
 * <p>The rule states one hypothesis so a sweep can measure it:
 *
 * <ol>
 *   <li>An observed integrity anomaly routes to review, never to an automatic reject. A visual
 *       anomaly is a reason for a person to look, not a finding of misconduct.</li>
 *   <li>An unscored observation routes to review, because absence of a score is not evidence
 *       against the submission.</li>
 *   <li>Otherwise relevanceScore is compared against the swept candidate threshold.</li>
 * </ol>
 *
 * <p>Only the threshold varies. The boolean observations are not searched: they have two states and
 * nothing to calibrate, so treating them as further search dimensions would add cost without
 * answering a question anyone has asked.
 */
final class CandidateDecision {

    private CandidateDecision() {
    }

    /**
     * @param threshold inclusive lower bound for a PASS candidate, within {@code [0,1]}
     */
    static AnalysisRecommendation evaluate(
            VerificationAnalysisObservation observation,
            BigDecimal threshold
    ) {
        Objects.requireNonNull(observation, "observation must not be null");
        requireThreshold(threshold);

        if (Boolean.TRUE.equals(observation.anomalyDetected())) {
            return AnalysisRecommendation.REVIEW_REQUIRED;
        }
        BigDecimal relevanceScore = observation.relevanceScore();
        if (relevanceScore == null) {
            return AnalysisRecommendation.REVIEW_REQUIRED;
        }
        return relevanceScore.compareTo(threshold) >= 0
                ? AnalysisRecommendation.PASS
                : AnalysisRecommendation.REJECT_CANDIDATE;
    }

    private static void requireThreshold(BigDecimal threshold) {
        Objects.requireNonNull(threshold, "threshold must not be null");
        if (threshold.signum() < 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("threshold must be between 0 and 1");
        }
    }
}

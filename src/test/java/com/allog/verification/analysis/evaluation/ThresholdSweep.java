package com.allog.verification.analysis.evaluation;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sweeps candidate relevanceScore thresholds across a labelled dataset and reports the trade-off at
 * each one. It deliberately reports and does not choose: picking a production threshold is a product
 * decision, and this harness exists to give that decision evidence, not to pre-empt it.
 *
 * <p>Step size is 0.05 over {@code [0,1]}, giving 21 candidates. The pilot dataset is small enough
 * that finer steps would return identical metrics between grid points while implying a precision the
 * sample cannot support.
 *
 * <p>Prior offline work on a different, video-based dataset found relevanceScore distributions for
 * clearly-valid and merely-ambiguous evidence overlapping almost completely. Those numbers belong to
 * another criteria contract and are not carried over, but the possibility that a single score cannot
 * separate these labels is exactly what this sweep is built to expose for meal-photo-record@1.
 */
final class ThresholdSweep {

    static final BigDecimal STEP = new BigDecimal("0.05");
    private static final int STEP_COUNT = 21;

    private ThresholdSweep() {
    }

    static List<BigDecimal> candidateThresholds() {
        List<BigDecimal> thresholds = new ArrayList<>(STEP_COUNT);
        for (int index = 0; index < STEP_COUNT; index++) {
            thresholds.add(STEP.multiply(BigDecimal.valueOf(index)).setScale(2, RoundingMode.UNNECESSARY));
        }
        return List.copyOf(thresholds);
    }

    static List<Result> sweep(List<ObservedCase> observedCases) {
        Objects.requireNonNull(observedCases, "observedCases must not be null");
        if (observedCases.isEmpty()) {
            throw new IllegalArgumentException("threshold sweep requires at least one observed case");
        }
        return candidateThresholds().stream()
                .map(threshold -> evaluateAt(threshold, observedCases))
                .toList();
    }

    private static Result evaluateAt(BigDecimal threshold, List<ObservedCase> observedCases) {
        List<CaseOutcome> outcomes = observedCases.stream()
                .map(observed -> new CaseOutcome(
                        observed.evaluationCase().caseId(),
                        observed.evaluationCase().humanLabel(),
                        CandidateDecision.evaluate(observed.observation(), threshold)
                ))
                .toList();
        return new Result(threshold, outcomes);
    }

    /** One dataset case paired with the provider observation collected for it. */
    record ObservedCase(EvaluationCase evaluationCase, VerificationAnalysisObservation observation) {

        ObservedCase {
            Objects.requireNonNull(evaluationCase, "evaluationCase must not be null");
            Objects.requireNonNull(observation, "observation must not be null");
        }
    }

    record CaseOutcome(String caseId, EvaluationHumanLabel humanLabel, AnalysisRecommendation outcome) {

        CaseOutcome {
            Objects.requireNonNull(caseId, "caseId must not be null");
            Objects.requireNonNull(humanLabel, "humanLabel must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    /**
     * Metrics at one candidate threshold. Everything is derived from {@link #outcomes()}, so the
     * headline counts and the per-label breakdown cannot drift apart.
     */
    record Result(BigDecimal threshold, List<CaseOutcome> outcomes) {

        Result {
            Objects.requireNonNull(threshold, "threshold must not be null");
            outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes must not be null"));
            if (outcomes.isEmpty()) {
                throw new IllegalArgumentException("outcomes must not be empty");
            }
        }

        int total() {
            return outcomes.size();
        }

        /**
         * A case a person judged to hold no assessable evidence, or to show a visual integrity
         * concern, that the candidate rule would nonetheless pass. This is the error that lets an
         * unsupported verification through, so it is reported on its own.
         */
        long falsePass() {
            return outcomes.stream()
                    .filter(outcome -> outcome.outcome() == AnalysisRecommendation.PASS)
                    .filter(outcome -> outcome.humanLabel() == EvaluationHumanLabel.INSUFFICIENT_EVIDENCE
                            || outcome.humanLabel() == EvaluationHumanLabel.POTENTIAL_INTEGRITY_ANOMALY)
                    .count();
        }

        /**
         * A case a person judged to hold plainly valid evidence that the candidate rule would treat
         * as a reject candidate. This is the error that penalises a member who did comply.
         *
         * <p>PARTIAL_OR_AMBIGUOUS_EVIDENCE is counted in neither error, because whether ambiguous
         * evidence ought to pass is an open product question. Scoring it either way here would
         * silently answer it.
         */
        long falseReject() {
            return outcomes.stream()
                    .filter(outcome -> outcome.outcome() == AnalysisRecommendation.REJECT_CANDIDATE)
                    .filter(outcome -> outcome.humanLabel() == EvaluationHumanLabel.CLEAR_VALID_EVIDENCE)
                    .count();
        }

        long reviewRequired() {
            return countOf(AnalysisRecommendation.REVIEW_REQUIRED);
        }

        /** Share of all cases routed to human review, i.e. the review burden this threshold implies. */
        double reviewRate() {
            return (double) reviewRequired() / total();
        }

        long countOf(AnalysisRecommendation outcome) {
            Objects.requireNonNull(outcome, "outcome must not be null");
            return outcomes.stream().filter(result -> result.outcome() == outcome).count();
        }

        long countOf(EvaluationHumanLabel label, AnalysisRecommendation outcome) {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            return outcomes.stream()
                    .filter(result -> result.humanLabel() == label && result.outcome() == outcome)
                    .count();
        }
    }
}

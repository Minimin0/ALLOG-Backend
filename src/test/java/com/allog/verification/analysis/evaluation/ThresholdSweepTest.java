package com.allog.verification.analysis.evaluation;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.template.VerificationTemplateCatalog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observations here are hand-built harness inputs, not collected provider output. No provider,
 * network call, API key, or Spring context is involved: the sweep is offline and deterministic.
 */
class ThresholdSweepTest {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal ONE = new BigDecimal("1.00");

    @Test
    void candidateThresholdsSpanZeroToOneInFixedAscendingSteps() {
        List<BigDecimal> thresholds = ThresholdSweep.candidateThresholds();

        assertAll(
                () -> assertEquals(21, thresholds.size()),
                () -> assertEquals(0, thresholds.getFirst().compareTo(ZERO)),
                () -> assertEquals(0, thresholds.getLast().compareTo(ONE)),
                () -> assertTrue(IntStream.range(1, thresholds.size())
                        .allMatch(index -> thresholds.get(index)
                                .subtract(thresholds.get(index - 1))
                                .compareTo(ThresholdSweep.STEP) == 0))
        );
    }

    @Test
    void sweepIsDeterministicAcrossRuns() {
        List<ThresholdSweep.ObservedCase> observed = mixedDataset();

        assertEquals(ThresholdSweep.sweep(observed), ThresholdSweep.sweep(observed));
    }

    @Test
    void atThresholdZeroEveryScoredObservationPasses() {
        ThresholdSweep.Result result = resultAt(ZERO, List.of(
                observed("a", EvaluationHumanLabel.CLEAR_VALID_EVIDENCE, scored("0.0000")),
                observed("b", EvaluationHumanLabel.INSUFFICIENT_EVIDENCE, scored("0.5000"))
        ));

        assertEquals(2, result.countOf(AnalysisRecommendation.PASS));
    }

    @Test
    void atThresholdOneOnlyAPerfectScorePasses() {
        ThresholdSweep.Result result = resultAt(ONE, List.of(
                observed("a", EvaluationHumanLabel.CLEAR_VALID_EVIDENCE, scored("1.0000")),
                observed("b", EvaluationHumanLabel.CLEAR_VALID_EVIDENCE, scored("0.9999"))
        ));

        assertAll(
                () -> assertEquals(1, result.countOf(AnalysisRecommendation.PASS)),
                () -> assertEquals(1, result.countOf(AnalysisRecommendation.REJECT_CANDIDATE))
        );
    }

    @Test
    void passCountNeverIncreasesAsThresholdRises() {
        List<ThresholdSweep.Result> results = ThresholdSweep.sweep(mixedDataset());

        assertTrue(IntStream.range(1, results.size())
                .allMatch(index -> results.get(index).countOf(AnalysisRecommendation.PASS)
                        <= results.get(index - 1).countOf(AnalysisRecommendation.PASS)));
    }

    @Test
    void anomalyRoutesToReviewAtEveryThresholdInsteadOfAutomaticReject() {
        List<ThresholdSweep.Result> results = ThresholdSweep.sweep(List.of(
                observed("anomaly", EvaluationHumanLabel.POTENTIAL_INTEGRITY_ANOMALY, anomalous("0.9000"))
        ));

        assertAll(
                () -> assertTrue(results.stream().allMatch(
                        result -> result.countOf(AnalysisRecommendation.REVIEW_REQUIRED) == 1
                )),
                () -> assertTrue(results.stream().allMatch(
                        result -> result.countOf(AnalysisRecommendation.REJECT_CANDIDATE) == 0
                )),
                () -> assertTrue(results.stream().allMatch(result -> result.falsePass() == 0))
        );
    }

    @Test
    void unscoredObservationRoutesToReviewAtEveryThreshold() {
        List<ThresholdSweep.Result> results = ThresholdSweep.sweep(List.of(
                observed("unscored", EvaluationHumanLabel.INSUFFICIENT_EVIDENCE, unscored())
        ));

        assertTrue(results.stream().allMatch(
                result -> result.countOf(AnalysisRecommendation.REVIEW_REQUIRED) == 1
        ));
    }

    @Test
    void falsePassCountsOnlyInsufficientOrAnomalyLabelsThatPass() {
        ThresholdSweep.Result result = resultAt(new BigDecimal("0.50"), List.of(
                observed("insufficient", EvaluationHumanLabel.INSUFFICIENT_EVIDENCE, scored("0.9000")),
                observed("clear", EvaluationHumanLabel.CLEAR_VALID_EVIDENCE, scored("0.9000")),
                observed("ambiguous", EvaluationHumanLabel.PARTIAL_OR_AMBIGUOUS_EVIDENCE, scored("0.9000"))
        ));

        assertAll(
                () -> assertEquals(1, result.falsePass()),
                () -> assertEquals(3, result.countOf(AnalysisRecommendation.PASS)),
                () -> assertEquals(1, result.countOf(
                        EvaluationHumanLabel.INSUFFICIENT_EVIDENCE, AnalysisRecommendation.PASS
                ))
        );
    }

    @Test
    void falseRejectCountsOnlyClearValidEvidenceThatIsRejected() {
        ThresholdSweep.Result result = resultAt(new BigDecimal("0.50"), List.of(
                observed("clear", EvaluationHumanLabel.CLEAR_VALID_EVIDENCE, scored("0.1000")),
                observed("insufficient", EvaluationHumanLabel.INSUFFICIENT_EVIDENCE, scored("0.1000"))
        ));

        assertAll(
                () -> assertEquals(1, result.falseReject()),
                () -> assertEquals(2, result.countOf(AnalysisRecommendation.REJECT_CANDIDATE))
        );
    }

    @Test
    void ambiguousEvidenceIsCountedInNeitherErrorMetric() {
        List<ThresholdSweep.ObservedCase> ambiguousOnly = List.of(
                observed("low", EvaluationHumanLabel.PARTIAL_OR_AMBIGUOUS_EVIDENCE, scored("0.1000")),
                observed("high", EvaluationHumanLabel.PARTIAL_OR_AMBIGUOUS_EVIDENCE, scored("0.9000"))
        );

        assertTrue(ThresholdSweep.sweep(ambiguousOnly).stream()
                .allMatch(result -> result.falsePass() == 0 && result.falseReject() == 0));
    }

    @Test
    void reviewRateIsShareOfCasesRoutedToReview() {
        ThresholdSweep.Result result = resultAt(new BigDecimal("0.50"), List.of(
                observed("anomaly", EvaluationHumanLabel.POTENTIAL_INTEGRITY_ANOMALY, anomalous("0.9000")),
                observed("clear", EvaluationHumanLabel.CLEAR_VALID_EVIDENCE, scored("0.9000")),
                observed("unrelated", EvaluationHumanLabel.INSUFFICIENT_EVIDENCE, scored("0.1000")),
                observed("unscored", EvaluationHumanLabel.INSUFFICIENT_EVIDENCE, unscored())
        ));

        assertAll(
                () -> assertEquals(4, result.total()),
                () -> assertEquals(2, result.reviewRequired()),
                () -> assertEquals(0.5d, result.reviewRate())
        );
    }

    /**
     * The harness reports trade-offs and must not name a winner. Choosing a production threshold is
     * a product decision that this dataset exists to inform, so no API here may pre-empt it.
     */
    @Test
    void harnessExposesNoSelectedOrRecommendedThreshold() {
        Stream<String> methodNames = Stream.concat(
                        Stream.of(ThresholdSweep.class.getDeclaredMethods()),
                        Stream.of(ThresholdSweep.Result.class.getDeclaredMethods())
                )
                .map(Method::getName)
                .map(name -> name.toLowerCase(Locale.ROOT));

        assertFalse(methodNames.anyMatch(name -> name.contains("best")
                || name.contains("select")
                || name.contains("recommended")
                || name.contains("optimal")
                || name.contains("chosen")));
    }

    @Test
    void rejectsEmptyDatasetAndOutOfRangeThreshold() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ThresholdSweep.sweep(List.of())
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> CandidateDecision.evaluate(scored("0.5000"), new BigDecimal("1.01"))
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> CandidateDecision.evaluate(scored("0.5000"), new BigDecimal("-0.01"))
                )
        );
    }

    private static ThresholdSweep.Result resultAt(
            BigDecimal threshold,
            List<ThresholdSweep.ObservedCase> observedCases
    ) {
        return ThresholdSweep.sweep(observedCases).stream()
                .filter(result -> result.threshold().compareTo(threshold) == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("threshold not in sweep: " + threshold));
    }

    private static List<ThresholdSweep.ObservedCase> mixedDataset() {
        return List.of(
                observed("clear", EvaluationHumanLabel.CLEAR_VALID_EVIDENCE, scored("0.9000")),
                observed("ambiguous", EvaluationHumanLabel.PARTIAL_OR_AMBIGUOUS_EVIDENCE, scored("0.6000")),
                observed("insufficient", EvaluationHumanLabel.INSUFFICIENT_EVIDENCE, scored("0.0200")),
                observed("anomaly", EvaluationHumanLabel.POTENTIAL_INTEGRITY_ANOMALY, anomalous("0.7000")),
                observed("unscored", EvaluationHumanLabel.INSUFFICIENT_EVIDENCE, unscored())
        );
    }

    private static ThresholdSweep.ObservedCase observed(
            String caseId,
            EvaluationHumanLabel label,
            VerificationAnalysisObservation observation
    ) {
        return new ThresholdSweep.ObservedCase(
                new EvaluationCase(
                        caseId,
                        VerificationTemplateCatalog.MEAL_PHOTO_RECORD,
                        VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1,
                        caseId + ".jpg",
                        label
                ),
                observation
        );
    }

    private static VerificationAnalysisObservation scored(String relevanceScore) {
        return new VerificationAnalysisObservation(
                true,
                new BigDecimal(relevanceScore),
                false,
                true,
                VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
        );
    }

    private static VerificationAnalysisObservation anomalous(String relevanceScore) {
        return new VerificationAnalysisObservation(
                true,
                new BigDecimal(relevanceScore),
                true,
                true,
                VerificationAnalysisObservation.ReasonCode.POTENTIAL_INTEGRITY_ANOMALY
        );
    }

    private static VerificationAnalysisObservation unscored() {
        return new VerificationAnalysisObservation(
                true,
                null,
                false,
                false,
                VerificationAnalysisObservation.ReasonCode.OBSERVATION_INSUFFICIENT
        );
    }
}

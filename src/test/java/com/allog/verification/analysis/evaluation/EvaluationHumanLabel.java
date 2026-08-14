package com.allog.verification.analysis.evaluation;

/**
 * Human ground truth for one evaluation case: what a person judges to be present in the photo.
 *
 * <p>This is deliberately NOT {@link com.allog.verification.analysis.domain.AnalysisRecommendation}.
 * A label states observed reality; a recommendation is a future Backend DecisionPolicy output.
 * Mapping one to the other is the product decision this dataset exists to inform, so the two
 * vocabularies must not be merged.
 *
 * <p>It is also NOT {@link com.allog.verification.analysis.domain.VerificationAnalysisObservation.ReasonCode}.
 * A ReasonCode reports how completely the provider could observe; a label reports what evidence
 * actually exists. A provider can observe completely that no meal is present, so the two answer
 * different questions and are not interchangeable.
 *
 * <p>Labelers judge only evidence visible in the photo. Actual consumption, healthiness, nutrition,
 * portion size, ownership, identity, capture time, deadline, and prior reuse are outside the
 * meal-photo-record@1 contract and must never influence a label.
 */
public enum EvaluationHumanLabel {

    /** A meal or food scene is plainly visible and assessable as meal-record evidence. */
    CLEAR_VALID_EVIDENCE,

    /** Food is visible but only partly, or it is genuinely arguable whether it is assessable. */
    PARTIAL_OR_AMBIGUOUS_EVIDENCE,

    /** No assessable meal evidence: unrelated subject, or too dark, blurred, or occluded to judge. */
    INSUFFICIENT_EVIDENCE,

    /**
     * The image itself shows a visual integrity concern, such as a re-photographed screen or
     * apparent compositing. This records an observation about the image, not a finding of
     * misconduct and not a reject decision.
     */
    POTENTIAL_INTEGRITY_ANOMALY
}

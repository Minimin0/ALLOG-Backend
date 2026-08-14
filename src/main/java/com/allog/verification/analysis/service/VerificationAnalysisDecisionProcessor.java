package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;

import java.util.Objects;

/**
 * Turns a provider observation into the backend's recommendation.
 *
 * <p>The provider reports what it saw and nothing else; the recommendation is decided here, from
 * two booleans. An observed integrity anomaly outranks everything, because a person should look at
 * it whether or not the meal is visible. Otherwise the only question is whether the evidence the
 * criteria asks for was present.
 *
 * <p>{@code relevanceScore}, {@code framedProperly} and {@code reasonCode} are recorded but decide
 * nothing. Seven real calls showed the first tracking {@code objectPresence} and the other two
 * carrying no independent signal, so no threshold is selected and none of them is read here.
 *
 * <p>A provider or media failure never reaches the policy: it is propagated as a failure, so a
 * timeout or a rate limit is never charged to the member as a rejected verification.
 */
public final class VerificationAnalysisDecisionProcessor implements VerificationAnalysisProcessor {

    private final VerificationAnalysisMediaProcessor mediaProcessor;

    public VerificationAnalysisDecisionProcessor(VerificationAnalysisMediaProcessor mediaProcessor) {
        this.mediaProcessor = Objects.requireNonNull(mediaProcessor, "mediaProcessor must not be null");
    }

    @Override
    public Outcome process(VerificationAnalysisClaim claim) {
        VerificationAnalysisMediaProcessor.Outcome outcome = mediaProcessor.process(claim);
        if (outcome instanceof VerificationAnalysisMediaProcessor.Failure failure) {
            return new Failure(failure.failureCode());
        }

        VerificationAnalysisMediaProcessor.Observed observed = (VerificationAnalysisMediaProcessor.Observed) outcome;
        return new Success(new VerificationAnalysisSuccessResult(
                recommend(observed.providerResult().observation()),
                // The criteria the media processor actually observed against, never a fresh lookup.
                observed.criteria().reference(),
                observed.providerResult()
        ));
    }

    /**
     * "Could not be assessed" is a different fact from "was assessed and was not there", so a
     * missing measurement is never read as a negative one. Deciding automatically requires both
     * measurements to actually exist; anything short of that is for a person to look at.
     */
    static AnalysisRecommendation recommend(VerificationAnalysisObservation observation) {
        Boolean anomalyDetected = observation.anomalyDetected();
        Boolean objectPresence = observation.objectPresence();
        if (anomalyDetected == null || objectPresence == null) {
            return AnalysisRecommendation.REVIEW_REQUIRED;
        }
        if (anomalyDetected) {
            return AnalysisRecommendation.REVIEW_REQUIRED;
        }
        return objectPresence ? AnalysisRecommendation.PASS : AnalysisRecommendation.REJECT_CANDIDATE;
    }
}

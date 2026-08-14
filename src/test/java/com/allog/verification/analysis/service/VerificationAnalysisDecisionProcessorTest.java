package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.template.VerificationTemplateCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationAnalysisDecisionProcessorTest {

    private static final VerificationAnalysisClaim CLAIM = new VerificationAnalysisClaim(10L, UUID.randomUUID(), 1);
    private static final VerificationCriteria CRITERIA = new VerificationTemplateCatalog()
            .requireCriteria(VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1);

    @Mock
    private VerificationAnalysisMediaProcessor mediaProcessor;

    @ParameterizedTest(name = "anomaly={0}, presence={1} -> {2}")
    @CsvSource({
            "true,  true,  REVIEW_REQUIRED",
            "true,  false, REVIEW_REQUIRED",
            "false, false, REJECT_CANDIDATE",
            "false, true,  PASS"
    })
    void decidesFromAnomalyAndPresenceAlone(
            boolean anomalyDetected,
            boolean objectPresence,
            AnalysisRecommendation expected
    ) {
        assertEquals(expected, VerificationAnalysisDecisionProcessor.recommend(
                observation(objectPresence, new BigDecimal("0.9"), anomalyDetected, true)
        ));
    }

    /** An unassessable measurement must not become a PASS. */
    @Test
    void treatsUnassessableMeasurementsAsNotPassing() {
        VerificationAnalysisObservation unassessable = new VerificationAnalysisObservation(
                null,
                null,
                null,
                null,
                VerificationAnalysisObservation.ReasonCode.OBSERVATION_INSUFFICIENT
        );

        assertEquals(
                AnalysisRecommendation.REJECT_CANDIDATE,
                VerificationAnalysisDecisionProcessor.recommend(unassessable)
        );
    }

    /**
     * The non-deciding observations vary while the deciding pair does not, so a threshold or a
     * framing rule sneaking into the policy would fail here.
     */
    @Test
    void ignoresRelevanceScoreFramingAndReasonCode() {
        AnalysisRecommendation lowScoreUnframed = VerificationAnalysisDecisionProcessor.recommend(
                new VerificationAnalysisObservation(
                        true,
                        new BigDecimal("0.05"),
                        false,
                        false,
                        VerificationAnalysisObservation.ReasonCode.OBSERVATION_PARTIAL
                )
        );
        AnalysisRecommendation highScoreFramed = VerificationAnalysisDecisionProcessor.recommend(
                new VerificationAnalysisObservation(
                        true,
                        new BigDecimal("1.0"),
                        false,
                        true,
                        VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
                )
        );

        assertAll(
                () -> assertEquals(AnalysisRecommendation.PASS, lowScoreUnframed),
                () -> assertEquals(AnalysisRecommendation.PASS, highScoreFramed)
        );
    }

    @Test
    void bridgesObservationToSuccessResultWithoutAlteringProviderEvidence() {
        VerificationAnalysisProvider.Result providerResult = new VerificationAnalysisProvider.Result(
                "synthetic-model",
                observation(true, new BigDecimal("0.98"), false, true)
        );
        when(mediaProcessor.process(CLAIM))
                .thenReturn(new VerificationAnalysisMediaProcessor.Observed(CRITERIA, providerResult));

        VerificationAnalysisProcessor.Outcome outcome = processor().process(CLAIM);

        VerificationAnalysisSuccessResult result =
                assertInstanceOf(VerificationAnalysisProcessor.Success.class, outcome).result();
        assertAll(
                () -> assertEquals(AnalysisRecommendation.PASS, result.recommendation()),
                () -> assertEquals(CRITERIA.reference(), result.criteriaReference()),
                () -> assertSame(providerResult, result.providerResult()),
                () -> assertSame(providerResult.observation(), result.providerResult().observation())
        );
    }

    /** A provider or media failure is a system failure, never a recommendation about the member. */
    @Test
    void propagatesFailureWithoutProducingARecommendation() {
        when(mediaProcessor.process(CLAIM)).thenReturn(
                new VerificationAnalysisMediaProcessor.Failure(VerificationAnalysisFailureCode.TIMEOUT)
        );

        VerificationAnalysisProcessor.Outcome outcome = processor().process(CLAIM);

        assertEquals(
                VerificationAnalysisFailureCode.TIMEOUT,
                assertInstanceOf(VerificationAnalysisProcessor.Failure.class, outcome).failureCode()
        );
    }

    private VerificationAnalysisDecisionProcessor processor() {
        return new VerificationAnalysisDecisionProcessor(mediaProcessor);
    }

    private VerificationAnalysisObservation observation(
            boolean objectPresence,
            BigDecimal relevanceScore,
            boolean anomalyDetected,
            boolean framedProperly
    ) {
        return new VerificationAnalysisObservation(
                objectPresence,
                relevanceScore,
                anomalyDetected,
                framedProperly,
                anomalyDetected
                        ? VerificationAnalysisObservation.ReasonCode.POTENTIAL_INTEGRITY_ANOMALY
                        : VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
        );
    }

}

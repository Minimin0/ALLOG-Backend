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

    /**
     * Every combination of the two deciding measurements, including the ones the provider could not
     * assess. A missing measurement never decides anything on its own: the row that matters most is
     * {@code null, true}, where reading a missing anomaly as "no anomaly" would auto-approve
     * evidence nobody checked for tampering.
     */
    @ParameterizedTest(name = "anomaly={0}, presence={1} -> {2}")
    @CsvSource(nullValues = "null", value = {
            "true,  true,  REVIEW_REQUIRED",
            "true,  false, REVIEW_REQUIRED",
            "true,  null,  REVIEW_REQUIRED",
            "false, true,  PASS",
            "false, false, REJECT_CANDIDATE",
            "false, null,  REVIEW_REQUIRED",
            "null,  true,  REVIEW_REQUIRED",
            "null,  false, REVIEW_REQUIRED",
            "null,  null,  REVIEW_REQUIRED"
    })
    void decidesFromAnomalyAndPresenceAlone(
            Boolean anomalyDetected,
            Boolean objectPresence,
            AnalysisRecommendation expected
    ) {
        assertEquals(expected, VerificationAnalysisDecisionProcessor.recommend(
                observation(objectPresence, new BigDecimal("0.9"), anomalyDetected)
        ));
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
                observation(true, new BigDecimal("0.98"), false)
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
            Boolean objectPresence,
            BigDecimal relevanceScore,
            Boolean anomalyDetected
    ) {
        return new VerificationAnalysisObservation(
                objectPresence,
                relevanceScore,
                anomalyDetected,
                true,
                Boolean.TRUE.equals(anomalyDetected)
                        ? VerificationAnalysisObservation.ReasonCode.POTENTIAL_INTEGRITY_ANOMALY
                        : VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
        );
    }
}

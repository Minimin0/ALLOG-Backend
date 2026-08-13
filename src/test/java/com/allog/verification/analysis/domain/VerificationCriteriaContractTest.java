package com.allog.verification.analysis.domain;

import com.allog.verification.analysis.service.VerificationAnalysisProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerificationCriteriaContractTest {

    @Test
    void createsImmutableVersionedCriteriaWithoutLeakingRoutineIdToProvider() {
        Set<VerificationCriteria.MediaModality> media = new HashSet<>(Set.of(
                VerificationCriteria.MediaModality.PHOTO
        ));
        VerificationCriteria criteria = criteria(media);
        media.add(VerificationCriteria.MediaModality.VIDEO);

        assertAll(
                () -> assertEquals("TEST_EVIDENCE@1", criteria.reference().storageValue()),
                () -> assertEquals(Set.of(VerificationCriteria.MediaModality.PHOTO), criteria.supportedMedia()),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> criteria.supportedMedia().add(VerificationCriteria.MediaModality.VIDEO)
                ),
                () -> assertFalse(Stream.of(criteria.providerContract().getClass().getRecordComponents())
                        .anyMatch(component -> component.getName().equals("routineDefinitionId")))
        );
    }

    @Test
    void rejectsInvalidIdentityVersionAndEmptyContract() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new VerificationCriteria.Reference(" ", 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new VerificationCriteria.Reference("INVALID@ID", 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new VerificationCriteria.Reference("TEST", 0)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new VerificationCriteria(
                                new VerificationCriteria.Reference("TEST", 1),
                                0,
                                Set.of(VerificationCriteria.MediaModality.PHOTO),
                                Set.of(VerificationCriteria.ObservationType.TARGET_EVIDENCE_VISIBLE),
                                "test evidence"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new VerificationCriteria(
                                new VerificationCriteria.Reference("TEST", 1),
                                1,
                                Set.of(),
                                Set.of(VerificationCriteria.ObservationType.TARGET_EVIDENCE_VISIBLE),
                                "test evidence"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new VerificationCriteria(
                                new VerificationCriteria.Reference("TEST", 1),
                                1,
                                Set.of(VerificationCriteria.MediaModality.PHOTO),
                                Set.of(),
                                "test evidence"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new VerificationCriteria(
                                new VerificationCriteria.Reference("TEST", 1),
                                1,
                                Set.of(VerificationCriteria.MediaModality.PHOTO),
                                Set.of(VerificationCriteria.ObservationType.TARGET_EVIDENCE_VISIBLE),
                                " "
                        )
                )
        );
    }

    @Test
    void observationsAllowExplicitUncertaintyButRejectInvalidScores() {
        VerificationAnalysisObservation uncertain = new VerificationAnalysisObservation(
                null,
                null,
                null,
                null,
                VerificationAnalysisObservation.ReasonCode.OBSERVATION_INSUFFICIENT
        );

        assertAll(
                () -> assertEquals(
                        VerificationAnalysisObservation.ReasonCode.OBSERVATION_INSUFFICIENT,
                        uncertain.reasonCode()
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> observation(new BigDecimal("-0.0001"))
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> observation(new BigDecimal("1.0001"))
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new VerificationAnalysisObservation(null, null, null, null, null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new VerificationAnalysisObservation(
                                null,
                                null,
                                false,
                                null,
                                VerificationAnalysisObservation.ReasonCode.POTENTIAL_INTEGRITY_ANOMALY
                        )
                )
        );
    }

    @Test
    void providerBoundaryExposesOnlyCriteriaMediaObservationsAndIntegrationMetadata() throws Exception {
        Method analyze = VerificationAnalysisProvider.class.getMethod(
                "analyze",
                VerificationCriteria.ProviderContract.class,
                VerificationAnalysisProvider.Evidence.class
        );
        byte[] source = {1, 2, 3};
        VerificationAnalysisProvider.Evidence evidence = new VerificationAnalysisProvider.Evidence(
                VerificationCriteria.MediaModality.PHOTO,
                "image/jpeg",
                source
        );
        source[0] = 9;
        byte[] returned = evidence.content();
        returned[1] = 9;

        assertAll(
                () -> assertEquals(VerificationAnalysisProvider.Result.class, analyze.getReturnType()),
                () -> assertFalse(Stream.of(VerificationAnalysisProvider.Result.class.getRecordComponents())
                        .anyMatch(component -> component.getName().equals("recommendation"))),
                () -> assertArrayEquals(new byte[]{1, 2, 3}, evidence.content())
        );
    }

    private VerificationCriteria criteria(Set<VerificationCriteria.MediaModality> media) {
        return new VerificationCriteria(
                new VerificationCriteria.Reference("TEST_EVIDENCE", 1),
                10L,
                media,
                Set.of(
                        VerificationCriteria.ObservationType.TARGET_EVIDENCE_VISIBLE,
                        VerificationCriteria.ObservationType.CRITERIA_RELEVANCE_SCORE,
                        VerificationCriteria.ObservationType.INTEGRITY_ANOMALY,
                        VerificationCriteria.ObservationType.FRAMING_SUFFICIENCY
                ),
                "Test-only evidence requirements"
        );
    }

    private VerificationAnalysisObservation observation(BigDecimal relevanceScore) {
        return new VerificationAnalysisObservation(
                true,
                relevanceScore,
                false,
                true,
                VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
        );
    }
}

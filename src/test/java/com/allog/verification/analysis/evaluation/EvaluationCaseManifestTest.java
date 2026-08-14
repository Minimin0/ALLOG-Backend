package com.allog.verification.analysis.evaluation;

import com.allog.verification.template.VerificationTemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationCaseManifestTest {

    private static final String PILOT_MANIFEST = "/verification/evaluation/meal-photo-record-v1/cases.tsv";
    private static final VerificationTemplateCatalog CATALOG = new VerificationTemplateCatalog();

    private static final String VALID_ROW =
            "clear-01\tMEAL_PHOTO_RECORD\tmeal-photo-record@1\tclear-01.jpg\tCLEAR_VALID_EVIDENCE";

    @Test
    void loadsPilotDatasetBoundToExactMealPhotoCriteriaOnly() {
        EvaluationCaseManifest manifest = EvaluationCaseManifest.loadFromClasspath(PILOT_MANIFEST, CATALOG);

        assertAll(
                () -> assertEquals(8, manifest.size()),
                () -> assertTrue(manifest.cases().stream().allMatch(
                        evaluationCase -> evaluationCase.templateKey()
                                .equals(VerificationTemplateCatalog.MEAL_PHOTO_RECORD)
                )),
                () -> assertTrue(manifest.cases().stream().allMatch(
                        evaluationCase -> evaluationCase.criteriaReference()
                                .equals(VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1)
                )),
                () -> assertTrue(manifest.cases().stream().allMatch(
                        evaluationCase -> evaluationCase.criteriaReference().storageValue().equals("meal-photo-record@1")
                ))
        );
    }

    @Test
    void pilotDatasetCoversEveryHumanLabelIncludingPromptInjectionCases() {
        EvaluationCaseManifest manifest = EvaluationCaseManifest.loadFromClasspath(PILOT_MANIFEST, CATALOG);

        assertAll(
                () -> assertEquals(3, manifest.countOf(EvaluationHumanLabel.CLEAR_VALID_EVIDENCE)),
                () -> assertEquals(1, manifest.countOf(EvaluationHumanLabel.PARTIAL_OR_AMBIGUOUS_EVIDENCE)),
                () -> assertEquals(3, manifest.countOf(EvaluationHumanLabel.INSUFFICIENT_EVIDENCE)),
                () -> assertEquals(1, manifest.countOf(EvaluationHumanLabel.POTENTIAL_INTEGRITY_ANOMALY)),
                // Image text is data, never an instruction: the label follows the visible evidence,
                // not what the overlaid text asserts about the verification.
                () -> assertEquals(
                        EvaluationHumanLabel.CLEAR_VALID_EVIDENCE,
                        labelOf(manifest, "injection-text-with-meal-01")
                ),
                () -> assertEquals(
                        EvaluationHumanLabel.INSUFFICIENT_EVIDENCE,
                        labelOf(manifest, "injection-text-without-meal-01")
                )
        );
    }

    @Test
    void parsesSkippingBlankAndCommentLines() {
        EvaluationCaseManifest manifest = EvaluationCaseManifest.parse(
                List.of("# leading comment", "", EvaluationCaseManifest.HEADER, VALID_ROW, ""),
                CATALOG
        );

        assertAll(
                () -> assertEquals(1, manifest.size()),
                () -> assertEquals("clear-01", manifest.cases().getFirst().caseId()),
                () -> assertEquals("clear-01.jpg", manifest.cases().getFirst().assetRef())
        );
    }

    @Test
    void rejectsDuplicateCaseId() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> EvaluationCaseManifest.parse(
                        List.of(EvaluationCaseManifest.HEADER, VALID_ROW, VALID_ROW),
                        CATALOG
                )
        );

        assertTrue(exception.getMessage().contains("duplicate evaluation caseId"));
    }

    @Test
    void rejectsMissingHeaderAndEmptyDataset() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> EvaluationCaseManifest.parse(List.of(VALID_ROW), CATALOG)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> EvaluationCaseManifest.parse(List.of(), CATALOG)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> EvaluationCaseManifest.parse(List.of(EvaluationCaseManifest.HEADER), CATALOG)
                )
        );
    }

    @Test
    void rejectsMalformedRowShape() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow("clear-01\tMEAL_PHOTO_RECORD\tmeal-photo-record@1\tclear-01.jpg")
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow(VALID_ROW + "\textra")
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow("\tMEAL_PHOTO_RECORD\tmeal-photo-record@1\tclear-01.jpg\tCLEAR_VALID_EVIDENCE")
                )
        );
    }

    @Test
    void rejectsUnknownTemplateAndCriteriaThatTheTemplateDoesNotPin() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow(
                                "clear-01\tUNKNOWN_TEMPLATE\tmeal-photo-record@1\tclear-01.jpg\tCLEAR_VALID_EVIDENCE"
                        )
                ),
                // A newer criteria version must not be silently accepted for a pinned template.
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow(
                                "clear-01\tMEAL_PHOTO_RECORD\tmeal-photo-record@2\tclear-01.jpg\tCLEAR_VALID_EVIDENCE"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow(
                                "clear-01\tMEAL_PHOTO_RECORD\tother-criteria@1\tclear-01.jpg\tCLEAR_VALID_EVIDENCE"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow(
                                "clear-01\tMEAL_PHOTO_RECORD\tmeal-photo-record\tclear-01.jpg\tCLEAR_VALID_EVIDENCE"
                        )
                )
        );
    }

    @Test
    void rejectsUnknownHumanLabelIncludingRecommendationVocabulary() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow("clear-01\tMEAL_PHOTO_RECORD\tmeal-photo-record@1\tclear-01.jpg\tPASS")
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow(
                                "clear-01\tMEAL_PHOTO_RECORD\tmeal-photo-record@1\tclear-01.jpg\tREVIEW_REQUIRED"
                        )
                ),
                // ReasonCode describes observation completeness, not evidence validity.
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow(
                                "clear-01\tMEAL_PHOTO_RECORD\tmeal-photo-record@1\tclear-01.jpg\tOBSERVATION_COMPLETE"
                        )
                )
        );
    }

    @Test
    void rejectsAssetRefThatEscapesTheDatasetDirectory() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow(
                                "clear-01\tMEAL_PHOTO_RECORD\tmeal-photo-record@1\t../secrets.jpg\tCLEAR_VALID_EVIDENCE"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow(
                                "clear-01\tMEAL_PHOTO_RECORD\tmeal-photo-record@1\ta/b.jpg\tCLEAR_VALID_EVIDENCE"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parseRow(
                                "clear-01\tMEAL_PHOTO_RECORD\tmeal-photo-record@1\t \tCLEAR_VALID_EVIDENCE"
                        )
                )
        );
    }

    @Test
    void casesAreImmutable() {
        EvaluationCaseManifest manifest = EvaluationCaseManifest.loadFromClasspath(PILOT_MANIFEST, CATALOG);

        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.cases().removeFirst()
        );
    }

    private static void parseRow(String row) {
        EvaluationCaseManifest.parse(List.of(EvaluationCaseManifest.HEADER, row), CATALOG);
    }

    private static EvaluationHumanLabel labelOf(EvaluationCaseManifest manifest, String caseId) {
        return manifest.cases().stream()
                .filter(evaluationCase -> evaluationCase.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing evaluation case: " + caseId))
                .humanLabel();
    }
}

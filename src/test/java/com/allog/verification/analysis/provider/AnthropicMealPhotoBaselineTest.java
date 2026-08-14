package com.allog.verification.analysis.provider;

import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.analysis.evaluation.EvaluationCase;
import com.allog.verification.analysis.evaluation.EvaluationCaseManifest;
import com.allog.verification.analysis.service.VerificationAnalysisProvider;
import com.allog.verification.template.VerificationTemplateCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Manual offline baseline run: one real Anthropic call per canonical case, recorded as-is.
 *
 * <p>Env-gated and never part of a normal test run, because every execution spends money. It
 * collects observations and writes them down; it does not assert that any observation is good, does
 * not retry, and does not decide anything. Judging the numbers is a human step performed against
 * the written artifact.
 *
 * <p>The provider is built through the production {@link AnthropicVerificationAnalysisConfiguration}
 * bean method, so a wiring defect in the shipped adapter shows up here rather than being masked by
 * a second evaluation-only client.
 *
 * <p>Run with:
 * {@code ANTHROPIC_API_KEY="$(security find-generic-password -a "$USER" -s ALLOG_ANTHROPIC_API_KEY -w)"
 * ALLOG_ANTHROPIC_BASELINE_RUN=1 ./gradlew test --tests '*AnthropicMealPhotoBaselineTest'}
 */
@EnabledIfEnvironmentVariable(named = "ALLOG_ANTHROPIC_BASELINE_RUN", matches = "1")
class AnthropicMealPhotoBaselineTest {

    private static final String DATASET_DIRECTORY = "/verification/evaluation/meal-photo-record-v1";
    private static final Path ARTIFACT_DIRECTORY =
            Path.of("src/test/resources/verification/evaluation/meal-photo-record-v1/baselines");
    private static final String CONTENT_TYPE = "image/jpeg";
    private static final String MODEL =
            System.getenv().getOrDefault("VERIFICATION_ANALYSIS_ANTHROPIC_MODEL", "claude-sonnet-5");
    private static final DateTimeFormatter EXECUTION_ID =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Excluded from the paid run: this fixture still carries an EXIF GPS position, and the adapter
     * sends stored bytes unchanged, so calling it would hand a real location to the provider. The
     * dataset is ground truth and is not edited from here.
     */
    private static final Set<String> LOCATION_METADATA_EXCLUSIONS = Set.of("rephotographed-screen-01");

    private static final String HEADER = String.join(
            "\t",
            "executionId", "model", "criteriaReference", "caseId", "humanLabel",
            "objectPresence", "relevanceScore", "anomalyDetected", "framedProperly", "reasonCode",
            "failureCode"
    );

    @Test
    void recordsOneObservationPerCanonicalCaseInManifestOrder() throws IOException {
        VerificationTemplateCatalog catalog = new VerificationTemplateCatalog();
        EvaluationCaseManifest manifest =
                EvaluationCaseManifest.loadFromClasspath(DATASET_DIRECTORY + "/cases.tsv", catalog);
        assertEquals(7, manifest.size(), "canonical MVP dataset must declare exactly 7 cases");

        VerificationCriteria.ProviderContract criteria = catalog
                .requireCriteria(VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1)
                .providerContract();
        VerificationAnalysisProvider provider = new AnthropicVerificationAnalysisConfiguration()
                .anthropicVerificationAnalysisProvider(new AnthropicVerificationAnalysisProperties(
                        true,
                        System.getenv("ANTHROPIC_API_KEY"),
                        MODEL,
                        null
                ));

        String executionId = EXECUTION_ID.format(Instant.now());
        List<String> rows = new ArrayList<>(List.of(HEADER));
        for (EvaluationCase evaluationCase : manifest.cases()) {
            if (LOCATION_METADATA_EXCLUSIONS.contains(evaluationCase.caseId())) {
                continue;
            }
            rows.add(observe(provider, criteria, evaluationCase, executionId));
        }

        Files.createDirectories(ARTIFACT_DIRECTORY);
        Files.write(
                ARTIFACT_DIRECTORY.resolve(executionId + "-" + MODEL + ".tsv"),
                rows,
                StandardCharsets.UTF_8
        );
    }

    /** Exactly one attempt per case. A failure is a baseline result and is written down like any other. */
    private String observe(
            VerificationAnalysisProvider provider,
            VerificationCriteria.ProviderContract criteria,
            EvaluationCase evaluationCase,
            String executionId
    ) throws IOException {
        VerificationAnalysisProvider.Evidence evidence = new VerificationAnalysisProvider.Evidence(
                VerificationCriteria.MediaModality.PHOTO,
                CONTENT_TYPE,
                Files.readAllBytes(datasetDirectory().resolve(evaluationCase.assetRef()))
        );
        try {
            return row(executionId, evaluationCase, provider.analyze(criteria, evidence).observation(), null);
        } catch (VerificationAnalysisProvider.ProviderException exception) {
            return row(executionId, evaluationCase, null, exception.failureCode());
        }
    }

    private String row(
            String executionId,
            EvaluationCase evaluationCase,
            VerificationAnalysisObservation observed,
            VerificationAnalysisFailureCode failureCode
    ) {
        return String.join(
                "\t",
                executionId,
                MODEL,
                evaluationCase.criteriaReference().storageValue(),
                evaluationCase.caseId(),
                evaluationCase.humanLabel().name(),
                text(observed == null ? null : observed.objectPresence()),
                text(observed == null ? null : observed.relevanceScore()),
                text(observed == null ? null : observed.anomalyDetected()),
                text(observed == null ? null : observed.framedProperly()),
                text(observed == null ? null : observed.reasonCode()),
                text(failureCode)
        );
    }

    /** Absent columns are written as "-" so that a TSV row never ends in an empty cell. */
    private static String text(Object value) {
        return value == null ? "-" : value.toString();
    }

    private Path datasetDirectory() {
        try {
            return Path.of(Objects.requireNonNull(
                    AnthropicMealPhotoBaselineTest.class.getResource(DATASET_DIRECTORY),
                    "dataset directory not found on the classpath"
            ).toURI()).normalize();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

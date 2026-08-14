package com.allog.verification.analysis.evaluation;

import com.allog.verification.template.VerificationTemplateCatalog;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the committed fixture bytes behind the manifest: an assetRef that does not resolve to a
 * real, decodable, non-duplicate image is a broken dataset, and a broken dataset silently produces
 * a meaningless calibration run.
 *
 * <p>The SHA-256 check here detects an accidentally duplicated fixture. It is not production
 * duplicate detection.
 */
class EvaluationAssetIntegrityTest {

    private static final String DATASET_DIRECTORY = "/verification/evaluation/meal-photo-record-v1";
    private static final String PILOT_MANIFEST = DATASET_DIRECTORY + "/cases.tsv";
    private static final VerificationTemplateCatalog CATALOG = new VerificationTemplateCatalog();

    private static final EvaluationCaseManifest MANIFEST =
            EvaluationCaseManifest.loadFromClasspath(PILOT_MANIFEST, CATALOG);

    @Test
    void everyActiveCaseResolvesToARealDecodableImageInsideTheDatasetDirectory() {
        Path datasetDirectory = datasetDirectory();

        assertEquals(7, MANIFEST.size(), "active MVP dataset must declare exactly 7 cases");
        MANIFEST.cases().forEach(evaluationCase -> {
            Path asset = datasetDirectory.resolve(evaluationCase.assetRef()).normalize();
            assertAll(
                    evaluationCase.caseId(),
                    () -> assertTrue(asset.startsWith(datasetDirectory), "asset escapes the dataset directory"),
                    () -> assertTrue(Files.isRegularFile(asset, LinkOption.NOFOLLOW_LINKS), "asset is not a regular file"),
                    () -> assertFalse(Files.isSymbolicLink(asset), "asset must not be a symlink"),
                    () -> assertTrue(size(asset) > 0, "asset must not be empty"),
                    () -> {
                        BufferedImage image = decode(asset);
                        assertNotNull(image, "asset must decode as an image");
                        assertTrue(image.getWidth() > 0 && image.getHeight() > 0, "asset must have real dimensions");
                    }
            );
        });
    }

    @Test
    void noTwoCasesShareTheSameImageBytes() {
        Path datasetDirectory = datasetDirectory();
        Map<String, String> caseIdByDigest = new HashMap<>();

        MANIFEST.cases().forEach(evaluationCase -> {
            String digest = sha256(datasetDirectory.resolve(evaluationCase.assetRef()));
            String previous = caseIdByDigest.put(digest, evaluationCase.caseId());
            assertEquals(
                    null,
                    previous,
                    () -> "duplicate fixture bytes shared by " + previous + " and " + evaluationCase.caseId()
            );
        });
    }

    private Path datasetDirectory() {
        try {
            return Path.of(
                    java.util.Objects.requireNonNull(
                            EvaluationAssetIntegrityTest.class.getResource(DATASET_DIRECTORY),
                            "dataset directory not found on the classpath"
                    ).toURI()
            ).normalize();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private BufferedImage decode(Path path) {
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

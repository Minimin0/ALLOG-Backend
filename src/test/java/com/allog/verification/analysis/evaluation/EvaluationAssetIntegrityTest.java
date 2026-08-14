package com.allog.verification.analysis.evaluation;

import com.allog.verification.template.VerificationTemplateCatalog;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
    private static final int APP1 = 0xE1;
    private static final int START_OF_SCAN = 0xDA;
    private static final int GPS_IFD_POINTER = 0x8825;
    private static final String EXIF_IDENTIFIER = "Exif\0\0";
    private static final String XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/";
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

    /**
     * A fixture is shipped to an external provider as stored bytes, so location metadata committed
     * here would leave the repository on the next evaluation run. One fixture did carry GPS
     * coordinates, which is why this is a test and not a one-off cleanup.
     */
    @Test
    void noCaseCarriesLocationMetadata() {
        Path datasetDirectory = datasetDirectory();

        MANIFEST.cases().forEach(evaluationCase -> assertFalse(
                carriesLocationMetadata(bytes(datasetDirectory.resolve(evaluationCase.assetRef()))),
                () -> evaluationCase.caseId() + " carries EXIF GPS or XMP location metadata"
        ));
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

    /** Walks JPEG marker segments only; the compressed scan is never searched for text. */
    private boolean carriesLocationMetadata(byte[] jpeg) {
        ByteBuffer buffer = ByteBuffer.wrap(jpeg).order(ByteOrder.BIG_ENDIAN);
        int index = 2;
        while (index + 4 <= jpeg.length && (jpeg[index] & 0xFF) == 0xFF) {
            int marker = jpeg[index + 1] & 0xFF;
            int length = buffer.getShort(index + 2) & 0xFFFF;
            if (marker == APP1 && isLocationBearing(jpeg, index + 4, length - 2)) {
                return true;
            }
            if (marker == START_OF_SCAN) {
                return false;
            }
            index += 2 + length;
        }
        return false;
    }

    private boolean isLocationBearing(byte[] jpeg, int offset, int length) {
        String header = new String(jpeg, offset, Math.min(length, XMP_NAMESPACE.length()), StandardCharsets.ISO_8859_1);
        if (header.startsWith(XMP_NAMESPACE)) {
            return new String(jpeg, offset, length, StandardCharsets.UTF_8).contains("GPS");
        }
        if (!header.startsWith(EXIF_IDENTIFIER)) {
            return false;
        }
        return hasGpsDirectory(jpeg, offset + EXIF_IDENTIFIER.length(), length - EXIF_IDENTIFIER.length());
    }

    private boolean hasGpsDirectory(byte[] jpeg, int offset, int length) {
        ByteBuffer tiff = ByteBuffer.wrap(jpeg, offset, length).slice();
        // "MM" and "II" are the byte order marks themselves, and 0x4D4D reads the same either way.
        tiff.order(tiff.getShort(0) == 0x4D4D ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        int rootDirectory = tiff.getInt(4);
        int entries = tiff.getShort(rootDirectory) & 0xFFFF;
        for (int entry = 0; entry < entries; entry++) {
            if ((tiff.getShort(rootDirectory + 2 + entry * 12) & 0xFFFF) == GPS_IFD_POINTER) {
                return true;
            }
        }
        return false;
    }

    private byte[] bytes(Path path) {
        try {
            return Files.readAllBytes(path);
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

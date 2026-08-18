package com.allog.verification.storage.local;

import com.allog.verification.storage.VerificationMediaProperties;
import com.allog.verification.storage.VerificationMediaStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalVerificationMediaStorageTest {
    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String KEY = "verification-media/7f066804-99ba-4d7a-a21c-2cd0b8aef9bd";

    @TempDir
    Path temporaryRoot;

    @Test
    void issuesSignedGrantWritesPrivatelyAndAcquiresExactBytes() throws Exception {
        LocalVerificationMediaStorage storage = storage();
        byte[] body = new byte[]{1, 2, 3, 4};
        VerificationMediaStorage.UploadGrant grant = grant(storage, body.length);

        storage.acceptUpload(
                opaqueId(grant.uri()),
                header(grant, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER),
                "image/jpeg",
                -1,
                "*",
                new ByteArrayInputStream(body)
        );

        VerificationMediaStorage.StoredMediaInspection inspection = storage.inspect(KEY);
        assertEquals(body.length, inspection.contentLength());
        assertEquals("image/jpeg", inspection.contentType());
        assertArrayEquals(body, storage.acquire(KEY, 10).content());
        Path media = temporaryRoot.resolve(KEY);
        assertTrueRegularFile(media);
        assertFalse(Files.isWritable(media.getParent().resolve(media.getFileName() + ".part")));
    }

    @Test
    void rejectsTamperedOrExpiredGrantWithoutWritingObject() {
        LocalVerificationMediaStorage storage = storage();
        VerificationMediaStorage.UploadGrant grant = grant(storage, 3);

        assertUploadFailure(
                LocalVerificationMediaStorage.UploadFailure.INVALID_GRANT,
                () -> storage.acceptUpload(
                        opaqueId(grant.uri()),
                        "tampered",
                        "image/jpeg",
                        3,
                        "*",
                        new ByteArrayInputStream(new byte[]{1, 2, 3})
                )
        );

        MutableClock mutableClock = new MutableClock(NOW);
        LocalVerificationMediaStorage expiringStorage = new LocalVerificationMediaStorage(
                properties(temporaryRoot.resolve("expired").toString()),
                mutableClock
        );
        VerificationMediaStorage.UploadGrant expired = expiringStorage.issueUpload(
                KEY,
                "image/jpeg",
                3,
                NOW.plusSeconds(1)
        );
        mutableClock.set(NOW.plusSeconds(2));
        assertUploadFailure(
                LocalVerificationMediaStorage.UploadFailure.EXPIRED_GRANT,
                () -> expiringStorage.acceptUpload(
                        opaqueId(expired.uri()),
                        header(expired, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER),
                        "image/jpeg",
                        3,
                        "*",
                        new ByteArrayInputStream(new byte[]{1, 2, 3})
                )
        );
        assertThrows(VerificationMediaStorage.StorageException.class, () -> storage.inspect(KEY));
    }

    @Test
    void rejectsWrongContentTypeSizeAndReplay() {
        LocalVerificationMediaStorage storage = storage();
        VerificationMediaStorage.UploadGrant wrongType = grant(storage, 3);
        assertUploadFailure(
                LocalVerificationMediaStorage.UploadFailure.INVALID_UPLOAD,
                () -> storage.acceptUpload(
                        opaqueId(wrongType.uri()),
                        header(wrongType, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER),
                        "image/png",
                        3,
                        "*",
                        new ByteArrayInputStream(new byte[]{1, 2, 3})
                )
        );

        VerificationMediaStorage.UploadGrant wrongSize = grant(storage, 3);
        assertUploadFailure(
                LocalVerificationMediaStorage.UploadFailure.INVALID_UPLOAD,
                () -> storage.acceptUpload(
                        opaqueId(wrongSize.uri()),
                        header(wrongSize, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER),
                        "image/jpeg",
                        2,
                        "*",
                        new ByteArrayInputStream(new byte[]{1, 2})
                )
        );

        VerificationMediaStorage.UploadGrant replay = grant(storage, 3);
        storage.acceptUpload(
                opaqueId(replay.uri()),
                header(replay, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER),
                "image/jpeg",
                3,
                "*",
                new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
        assertUploadFailure(
                LocalVerificationMediaStorage.UploadFailure.INVALID_GRANT,
                () -> storage.acceptUpload(
                        opaqueId(replay.uri()),
                        header(replay, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER),
                        "image/jpeg",
                        3,
                        "*",
                        new ByteArrayInputStream(new byte[]{1, 2, 3})
                )
        );
    }

    @Test
    void rejectsOversizeIssueAndCleansPartialFileAfterShortBody() throws Exception {
        LocalVerificationMediaStorage storage = storage();
        assertThrows(
                VerificationMediaStorage.StorageException.class,
                () -> storage.issueUpload(KEY, "image/jpeg", 11, NOW.plusSeconds(60))
        );

        VerificationMediaStorage.UploadGrant grant = grant(storage, 4);
        assertUploadFailure(
                LocalVerificationMediaStorage.UploadFailure.INVALID_UPLOAD,
                () -> storage.acceptUpload(
                        opaqueId(grant.uri()),
                        header(grant, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER),
                        "image/jpeg",
                        4,
                        "*",
                        new ByteArrayInputStream(new byte[]{1, 2, 3})
                )
        );
        VerificationMediaStorage.UploadGrant extraBody = grant(storage, 3);
        assertUploadFailure(
                LocalVerificationMediaStorage.UploadFailure.INVALID_UPLOAD,
                () -> storage.acceptUpload(
                        opaqueId(extraBody.uri()),
                        header(extraBody, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER),
                        "image/jpeg",
                        3,
                        "*",
                        new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
                )
        );
        Path object = temporaryRoot.resolve(KEY);
        assertFalse(Files.exists(object));
        assertFalse(Files.exists(object.resolveSibling(object.getFileName() + ".meta")));
    }

    @Test
    void rejectsTraversalAndDoesNotOverwriteExistingObject() {
        LocalVerificationMediaStorage storage = storage();
        assertThrows(
                VerificationMediaStorage.StorageException.class,
                () -> storage.issueUpload("../outside", "image/jpeg", 3, NOW.plusSeconds(60))
        );

        VerificationMediaStorage.UploadGrant first = grant(storage, 3);
        storage.acceptUpload(
                opaqueId(first.uri()),
                header(first, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER),
                "image/jpeg",
                3,
                "*",
                new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
        VerificationMediaStorage.UploadGrant second = grant(storage, 3);
        assertUploadFailure(
                LocalVerificationMediaStorage.UploadFailure.OVERWRITE,
                () -> storage.acceptUpload(
                        opaqueId(second.uri()),
                        header(second, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER),
                        "image/jpeg",
                        3,
                        "*",
                        new ByteArrayInputStream(new byte[]{4, 5, 6})
                )
        );
        assertArrayEquals(new byte[]{1, 2, 3}, storage.acquire(KEY, 10).content());
    }

    @Test
    void validatesLocalConfiguration() {
        VerificationMediaProperties local = properties(temporaryRoot.toString());
        local.validateEnabledConfiguration();

        VerificationMediaProperties invalid = new VerificationMediaProperties(
                true,
                10,
                java.time.Duration.ofMinutes(1),
                Set.of("image/jpeg"),
                "",
                "not a URL",
                "short"
        );
        assertThrows(IllegalStateException.class, invalid::validateEnabledConfiguration);
    }

    private LocalVerificationMediaStorage storage() {
        return new LocalVerificationMediaStorage(properties(temporaryRoot.toString()), CLOCK);
    }

    private VerificationMediaProperties properties(String root) {
        return new VerificationMediaProperties(
                true,
                10,
                java.time.Duration.ofMinutes(1),
                Set.of("image/jpeg", "image/png"),
                root,
                "https://api.allog-app.store",
                SECRET
        );
    }

    private VerificationMediaStorage.UploadGrant grant(LocalVerificationMediaStorage storage, int size) {
        return storage.issueUpload(KEY, "image/jpeg", size, NOW.plusSeconds(60));
    }

    private String opaqueId(URI uri) {
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String header(VerificationMediaStorage.UploadGrant grant, String name) {
        List<String> values = grant.requiredHeaders().get(name);
        return values.getFirst();
    }

    private void assertUploadFailure(
            LocalVerificationMediaStorage.UploadFailure expected,
            Runnable action
    ) {
        LocalVerificationMediaStorage.UploadException exception = assertThrows(
                LocalVerificationMediaStorage.UploadException.class,
                action::run
        );
        assertEquals(expected, exception.failure());
    }

    private void assertTrueRegularFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("expected regular private media file");
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        void set(Instant next) {
            instant.set(next);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}

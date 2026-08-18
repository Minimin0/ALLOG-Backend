package com.allog.verification.storage.local;

import com.allog.verification.storage.VerificationMediaProperties;
import com.allog.verification.storage.VerificationMediaStorage;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class LocalVerificationMediaStorage implements VerificationMediaStorage {
    public static final String UPLOAD_SIGNATURE_HEADER = "x-allog-upload-signature";
    private static final String UPLOAD_PATH = "/api/v1/verification-media/uploads/";
    private static final String OBJECT_KEY_PATTERN = "verification-media/[0-9a-fA-F-]{36}";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ
    );

    private final VerificationMediaProperties properties;
    private final Clock clock;
    private final Path root;
    private final URI baseUrl;
    private final byte[] signingSecret;
    private final Map<String, UploadGrantState> grants = new ConcurrentHashMap<>();
    private final Map<String, String> activeGrantIdsByObjectKey = new ConcurrentHashMap<>();
    private final Object grantCleanupMonitor = new Object();
    private Instant nextGrantCleanupAt = Instant.MAX;

    public LocalVerificationMediaStorage(VerificationMediaProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        properties.validateEnabledConfiguration();
        try {
            root = Path.of(properties.localRoot()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (!Files.isDirectory(root)) {
                throw new IOException("local verification media root is not a directory");
            }
        } catch (IOException | RuntimeException exception) {
            throw new StorageException(
                    StorageException.Reason.CONFIGURATION,
                    "local verification media root is not usable",
                    exception
            );
        }
        String configuredBaseUrl = properties.localBaseUrl();
        baseUrl = URI.create(configuredBaseUrl.endsWith("/")
                ? configuredBaseUrl.substring(0, configuredBaseUrl.length() - 1)
                : configuredBaseUrl);
        signingSecret = properties.localSigningSecret().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public UploadGrant issueUpload(String objectKey, String contentType, long sizeBytes, Instant expiresAt) {
        String safeKey = requireObjectKey(objectKey);
        String normalizedContentType = VerificationMediaProperties.normalizeContentType(contentType);
        if (sizeBytes <= 0 || sizeBytes > properties.maxBytes()) {
            throw new StorageException(StorageException.Reason.CONFIGURATION, "local upload size is invalid");
        }
        if (expiresAt == null || !clock.instant().isBefore(expiresAt)) {
            throw new StorageException(StorageException.Reason.CONFIGURATION, "local upload expiry must be in the future");
        }
        UploadGrantState state = new UploadGrantState(
                UUID.randomUUID().toString(),
                safeKey,
                normalizedContentType,
                sizeBytes,
                expiresAt
        );
        registerGrant(state);
        return new UploadGrant(
                baseUrl.resolve(UPLOAD_PATH + state.id()),
                "PUT",
                Map.of(
                        "content-type", List.of(normalizedContentType),
                        "if-none-match", List.of("*"),
                        UPLOAD_SIGNATURE_HEADER, List.of(signature(state))
                ),
                expiresAt
        );
    }

    public void acceptUpload(
            String opaqueId,
            String signature,
            String contentType,
            long contentLength,
            String ifNoneMatch,
            InputStream body
    ) {
        UploadGrantState state = grants.get(opaqueId);
        if (state == null) {
            throw new UploadException(UploadFailure.INVALID_GRANT);
        }
        if (!clock.instant().isBefore(state.expiresAt())) {
            discardGrant(opaqueId, state);
            throw new UploadException(UploadFailure.EXPIRED_GRANT);
        }
        if (signature == null || !MessageDigest.isEqual(
                signature(state).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new UploadException(UploadFailure.INVALID_GRANT);
        }
        String normalizedContentType;
        try {
            normalizedContentType = normalizeUploadedContentType(contentType);
        } catch (IllegalArgumentException exception) {
            throw new UploadException(UploadFailure.INVALID_UPLOAD);
        }
        if (!state.contentType().equals(normalizedContentType)
                || !properties.allowedContentTypes().contains(normalizedContentType)
                || (contentLength != -1 && contentLength != state.sizeBytes())
                || (contentLength != -1 && (contentLength <= 0 || contentLength > properties.maxBytes()))
                || !"*".equals(ifNoneMatch)
                || !consumeGrant(opaqueId, state)) {
            throw new UploadException(UploadFailure.INVALID_UPLOAD);
        }
        Path target = target(state.objectKey());
        Path metadata = metadata(target);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        boolean claimed = false;
        boolean completed = false;
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(metadata)) {
                throw new UploadException(UploadFailure.OVERWRITE);
            }
            claimTarget(target);
            claimed = true;
            long copied;
            try (InputStream input = body; var output = Files.newOutputStream(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                restrictPermissions(temporary);
                copied = copyExactly(input, output, state.sizeBytes());
            }
            if (copied != state.sizeBytes()) {
                throw new UploadException(UploadFailure.INVALID_UPLOAD);
            }
            replaceClaimedTarget(temporary, target);
            writeMetadata(metadata, state.contentType(), copied);
            completed = true;
        } catch (UploadException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException(
                    StorageException.Reason.UNAVAILABLE,
                    "local verification media upload failed",
                    exception
            );
        } finally {
            try {
                Files.deleteIfExists(temporary);
                if (claimed && !completed) {
                    Files.deleteIfExists(metadata);
                    Files.deleteIfExists(target);
                }
            } catch (IOException ignored) {
                // The unexposed partial state may be cleaned operationally.
            }
        }
    }

    @Override
    public StoredMediaInspection inspect(String objectKey) {
        Path target = target(objectKey);
        Path metadata = metadata(target);
        try {
            if (!Files.isRegularFile(target) || !Files.isRegularFile(metadata)) {
                throw new StorageException(StorageException.Reason.NOT_FOUND, "verification media object was not found");
            }
            Metadata stored = readMetadata(metadata);
            long actualSize = Files.size(target);
            if (actualSize != stored.size() || actualSize <= 0) {
                throw new StorageException(
                        StorageException.Reason.CONFIGURATION,
                        "local verification media metadata does not match the object"
                );
            }
            return new StoredMediaInspection(requireObjectKey(objectKey), actualSize, stored.contentType());
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException(
                    StorageException.Reason.UNAVAILABLE,
                    "local verification media inspection failed",
                    exception
            );
        }
    }

    @Override
    public StoredMedia acquire(String objectKey, long maxBytes) {
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE - 1) {
            throw new IllegalArgumentException("maxBytes must be between 1 and Integer.MAX_VALUE - 1");
        }
        StoredMediaInspection inspection = inspect(objectKey);
        if (inspection.contentLength() > maxBytes) {
            throw new StorageException(
                    StorageException.Reason.CONFIGURATION,
                    "local verification media exceeds the requested maximum size"
            );
        }
        try {
            byte[] content = Files.readAllBytes(target(objectKey));
            if (content.length != inspection.contentLength()) {
                throw new StorageException(
                        StorageException.Reason.CONFIGURATION,
                        "local verification media changed while being read"
                );
            }
            return new StoredMedia(
                    inspection.objectKey(),
                    content.length,
                    inspection.contentType(),
                    content
            );
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException(
                    StorageException.Reason.UNAVAILABLE,
                    "local verification media acquisition failed",
                    exception
            );
        }
    }

    @Override
    public synchronized void overwrite(String objectKey, String contentType, byte[] content) {
        String safeKey = requireObjectKey(objectKey);
        String normalizedContentType = VerificationMediaProperties.normalizeContentType(contentType);
        Objects.requireNonNull(content, "content must not be null");
        if (content.length == 0 || content.length > properties.maxBytes()) {
            throw new StorageException(StorageException.Reason.CONFIGURATION, "local verification media overwrite size is invalid");
        }
        Path target = target(safeKey);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            if (!Files.isRegularFile(target)) {
                throw new StorageException(StorageException.Reason.NOT_FOUND, "verification media object was not found");
            }
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrictPermissions(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            writeMetadata(metadata(target), normalizedContentType, content.length);
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException(
                    StorageException.Reason.UNAVAILABLE,
                    "local verification media overwrite failed",
                    exception
            );
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // See acceptUpload cleanup.
            }
        }
    }

    private void registerGrant(UploadGrantState state) {
        synchronized (grantCleanupMonitor) {
            cleanupExpiredGrantsLocked(clock.instant());
            String previousId = activeGrantIdsByObjectKey.put(state.objectKey(), state.id());
            if (previousId != null) {
                grants.remove(previousId);
            }
            grants.put(state.id(), state);
            if (state.expiresAt().isBefore(nextGrantCleanupAt)) {
                nextGrantCleanupAt = state.expiresAt();
            }
        }
    }

    private boolean consumeGrant(String opaqueId, UploadGrantState state) {
        if (!grants.remove(opaqueId, state)) {
            return false;
        }
        activeGrantIdsByObjectKey.remove(state.objectKey(), opaqueId);
        return true;
    }

    private void discardGrant(String opaqueId, UploadGrantState state) {
        if (grants.remove(opaqueId, state)) {
            activeGrantIdsByObjectKey.remove(state.objectKey(), opaqueId);
        }
    }

    private void cleanupExpiredGrantsLocked(Instant now) {
        if (now.isBefore(nextGrantCleanupAt)) {
            return;
        }
        grants.forEach((opaqueId, state) -> {
            if (!now.isBefore(state.expiresAt()) && grants.remove(opaqueId, state)) {
                activeGrantIdsByObjectKey.remove(state.objectKey(), opaqueId);
            }
        });
        nextGrantCleanupAt = grants.values().stream()
                .map(UploadGrantState::expiresAt)
                .min(Instant::compareTo)
                .orElse(Instant.MAX);
    }

    int outstandingGrantCount() {
        return grants.size();
    }

    private void claimTarget(Path target) throws IOException {
        try {
            Files.createFile(target);
            restrictPermissions(target);
        } catch (FileAlreadyExistsException exception) {
            throw new UploadException(UploadFailure.OVERWRITE);
        }
    }

    private static long copyExactly(InputStream input, OutputStream output, long expectedBytes)
            throws IOException {
        byte[] buffer = new byte[8 * 1024];
        long copied = 0;
        while (copied < expectedBytes) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, expectedBytes - copied));
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            copied += read;
        }
        if (copied != expectedBytes || input.read() != -1) {
            throw new UploadException(UploadFailure.INVALID_UPLOAD);
        }
        return copied;
    }

    private void replaceClaimedTarget(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeMetadata(Path path, String contentType, long size) throws IOException {
        Files.writeString(
                path,
                contentType + "\n" + size + "\n",
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        restrictPermissions(path);
    }

    private Metadata readMetadata(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.US_ASCII);
        if (lines.size() != 2 || lines.get(0).isBlank()) {
            throw new IOException("invalid local verification media metadata");
        }
        try {
            return new Metadata(
                    VerificationMediaProperties.normalizeContentType(lines.get(0)),
                    Long.parseLong(lines.get(1))
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid local verification media metadata", exception);
        }
    }

    private void restrictPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PRIVATE_FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Test filesystems and Windows may not expose POSIX permissions.
        }
    }

    private Path target(String objectKey) {
        String safeKey = requireObjectKey(objectKey);
        Path resolved = root.resolve(safeKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException(
                    StorageException.Reason.CONFIGURATION,
                    "local verification media key is outside the configured root"
            );
        }
        return resolved;
    }

    private Path metadata(Path target) {
        return target.resolveSibling(target.getFileName() + ".meta");
    }

    private String requireObjectKey(String objectKey) {
        if (objectKey == null || !objectKey.matches(OBJECT_KEY_PATTERN)) {
            throw new StorageException(StorageException.Reason.CONFIGURATION, "local verification media key is invalid");
        }
        return objectKey;
    }

    private static String normalizeUploadedContentType(String contentType) {
        MediaType mediaType = MediaType.parseMediaType(contentType == null ? "" : contentType.trim());
        if (mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
            throw new IllegalArgumentException("content type must be explicit");
        }
        if (mediaType.getParameters().keySet().stream()
                .anyMatch(parameter -> !"charset".equalsIgnoreCase(parameter))) {
            throw new IllegalArgumentException("content type has unsupported parameters");
        }
        return (mediaType.getType() + "/" + mediaType.getSubtype())
                .toLowerCase(Locale.ROOT);
    }

    private String signature(UploadGrantState state) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingSecret, HMAC_ALGORITHM));
            String payload = String.join(
                    "\n",
                    "PUT",
                    state.id(),
                    state.objectKey(),
                    state.contentType(),
                    Long.toString(state.sizeBytes()),
                    Long.toString(state.expiresAt().toEpochMilli())
            );
            return java.util.HexFormat.of().formatHex(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException exception) {
            throw new StorageException(
                    StorageException.Reason.CONFIGURATION,
                    "local verification media signing is unavailable",
                    exception
            );
        }
    }

    private record UploadGrantState(
            String id,
            String objectKey,
            String contentType,
            long sizeBytes,
            Instant expiresAt
    ) {
    }

    private record Metadata(String contentType, long size) {
    }

    public enum UploadFailure {
        INVALID_GRANT,
        EXPIRED_GRANT,
        INVALID_UPLOAD,
        OVERWRITE
    }

    public static final class UploadException extends RuntimeException {
        private final UploadFailure failure;

        public UploadException(UploadFailure failure) {
            this.failure = failure;
        }

        public UploadFailure failure() {
            return failure;
        }
    }
}

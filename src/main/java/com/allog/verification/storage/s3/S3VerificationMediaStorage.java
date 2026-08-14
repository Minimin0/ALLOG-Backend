package com.allog.verification.storage.s3;

import com.allog.verification.storage.VerificationMediaStorage;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class S3VerificationMediaStorage implements VerificationMediaStorage {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Clock clock;

    public S3VerificationMediaStorage(
            S3Client s3Client,
            S3Presigner presigner,
            String bucket,
            Clock clock
    ) {
        this.s3Client = Objects.requireNonNull(s3Client);
        this.presigner = Objects.requireNonNull(presigner);
        this.bucket = requireText(bucket, "bucket");
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public UploadGrant issueUpload(
            String objectKey,
            String contentType,
            long sizeBytes,
            Instant expiresAt
    ) {
        Duration signatureDuration = Duration.between(clock.instant(), Objects.requireNonNull(expiresAt));
        if (signatureDuration.isZero() || signatureDuration.isNegative()) {
            throw new StorageException(
                    StorageException.Reason.CONFIGURATION,
                    "verification media upload expiry must be in the future"
            );
        }

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(requireText(objectKey, "objectKey"))
                .contentType(requireText(contentType, "contentType"))
                .contentLength(sizeBytes)
                .ifNoneMatch("*")
                .build();
        try {
            PresignedPutObjectRequest presigned = presigner.presignPutObject(
                    PutObjectPresignRequest.builder()
                            .signatureDuration(signatureDuration)
                            .putObjectRequest(request)
                            .build()
            );
            Map<String, java.util.List<String>> requiredHeaders = presigned.signedHeaders().entrySet().stream()
                    .filter(entry -> !entry.getKey().equalsIgnoreCase("host"))
                    .collect(Collectors.toUnmodifiableMap(
                            entry -> entry.getKey().toLowerCase(Locale.ROOT),
                            Map.Entry::getValue
            ));
            return new UploadGrant(
                    URI.create(presigned.url().toString()),
                    presigned.httpRequest().method().name(),
                    requiredHeaders,
                    expiresAt
            );
        } catch (SdkClientException exception) {
            throw unavailable("verification media upload grant could not be generated", exception);
        } catch (RuntimeException exception) {
            throw configuration("verification media upload grant configuration is invalid", exception);
        }
    }

    @Override
    public StoredMediaInspection inspect(String objectKey) {
        String expectedObjectKey = requireText(objectKey, "objectKey");
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(expectedObjectKey)
                    .build());
            return new StoredMediaInspection(
                    expectedObjectKey,
                    response.contentLength(),
                    response.contentType()
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new StorageException(
                        StorageException.Reason.NOT_FOUND,
                        "verification media object was not found",
                        exception
                );
            }
            if (exception.statusCode() >= 500) {
                throw unavailable("verification media storage is unavailable", exception);
            }
            throw configuration("verification media storage request was rejected", exception);
        } catch (SdkClientException exception) {
            throw unavailable("verification media storage is unavailable", exception);
        }
    }

    @Override
    public StoredMedia acquire(String objectKey, long maxBytes) {
        String expectedObjectKey = requireText(objectKey, "objectKey");
        if (maxBytes <= 0 || maxBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes must be between 1 and Integer.MAX_VALUE - 1");
        }
        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(expectedObjectKey)
                .build())) {
            byte[] content = response.readNBytes(Math.toIntExact(maxBytes + 1));
            GetObjectResponse metadata = response.response();
            Long contentLength = metadata.contentLength();
            return new StoredMedia(
                    expectedObjectKey,
                    contentLength == null ? -1 : contentLength,
                    metadata.contentType(),
                    content
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new StorageException(
                        StorageException.Reason.NOT_FOUND,
                        "verification media object was not found",
                        exception
                );
            }
            if (exception.statusCode() >= 500) {
                throw unavailable("verification media storage is unavailable", exception);
            }
            throw configuration("verification media storage request was rejected", exception);
        } catch (SdkClientException | IOException exception) {
            throw unavailable("verification media storage is unavailable", exception);
        }
    }

    @Override
    public void overwrite(String objectKey, String contentType, byte[] content) {
        String expectedObjectKey = requireText(objectKey, "objectKey");
        String expectedContentType = requireText(contentType, "contentType");
        Objects.requireNonNull(content, "content must not be null");
        if (content.length == 0) {
            throw new IllegalArgumentException("content must not be empty");
        }
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(expectedObjectKey)
                            .contentType(expectedContentType)
                            .contentLength((long) content.length)
                            .build(),
                    RequestBody.fromBytes(content)
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() >= 500) {
                throw unavailable("verification media storage is unavailable", exception);
            }
            throw configuration("verification media storage request was rejected", exception);
        } catch (SdkClientException exception) {
            throw unavailable("verification media storage is unavailable", exception);
        }
    }

    private StorageException unavailable(String message, Throwable cause) {
        return new StorageException(StorageException.Reason.UNAVAILABLE, message, cause);
    }

    private StorageException configuration(String message, Throwable cause) {
        return new StorageException(StorageException.Reason.CONFIGURATION, message, cause);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

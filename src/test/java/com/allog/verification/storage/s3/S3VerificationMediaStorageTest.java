package com.allog.verification.storage.s3;

import com.allog.verification.storage.VerificationMediaStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3VerificationMediaStorageTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "verification-media/test";

    private S3Client s3Client;
    private S3Presigner presigner;
    private S3VerificationMediaStorage storage;
    private Clock clock;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        getClass().getName(),
                        getClass().getPackageName()
                )))
                .build();
        clock = Clock.systemUTC();
        storage = new S3VerificationMediaStorage(s3Client, presigner, BUCKET, clock);
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void presignsBackendOwnedConditionalPutWithRequiredHeaders() {
        Instant expiresAt = clock.instant().plusSeconds(300);

        VerificationMediaStorage.UploadGrant grant = storage.issueUpload(
                KEY,
                "video/mp4",
                123,
                expiresAt
        );

        assertAll(
                () -> assertEquals("PUT", grant.method()),
                () -> assertTrue(grant.uri().getPath().endsWith("/" + KEY)),
                () -> assertEquals("video/mp4", grant.requiredHeaders().get("content-type").getFirst()),
                () -> assertEquals("*", grant.requiredHeaders().get("if-none-match").getFirst()),
                () -> assertEquals("123", grant.requiredHeaders().get("content-length").getFirst()),
                () -> assertEquals(expiresAt, grant.expiresAt())
        );
    }

    @Test
    void inspectsConfiguredBucketAndExactKey() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(123L)
                .contentType("video/mp4")
                .build());

        VerificationMediaStorage.StoredMediaInspection result = storage.inspect(KEY);

        ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(request.capture());
        assertAll(
                () -> assertEquals(BUCKET, request.getValue().bucket()),
                () -> assertEquals(KEY, request.getValue().key()),
                () -> assertEquals(KEY, result.objectKey()),
                () -> assertEquals(123, result.contentLength()),
                () -> assertEquals("video/mp4", result.contentType())
        );
    }

    @Test
    void mapsMissingAndUnavailableHeadSeparately() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("missing").build())
                .thenThrow(S3Exception.builder().statusCode(503).message("unavailable").build());

        VerificationMediaStorage.StorageException missing = assertThrows(
                VerificationMediaStorage.StorageException.class,
                () -> storage.inspect(KEY)
        );
        VerificationMediaStorage.StorageException unavailable = assertThrows(
                VerificationMediaStorage.StorageException.class,
                () -> storage.inspect(KEY)
        );

        assertAll(
                () -> assertEquals(VerificationMediaStorage.StorageException.Reason.NOT_FOUND, missing.reason()),
                () -> assertEquals(VerificationMediaStorage.StorageException.Reason.UNAVAILABLE, unavailable.reason())
        );
    }

    @Test
    void mapsRejectedHeadAsConfigurationFailure() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("denied").build());

        VerificationMediaStorage.StorageException exception = assertThrows(
                VerificationMediaStorage.StorageException.class,
                () -> storage.inspect(KEY)
        );

        assertEquals(VerificationMediaStorage.StorageException.Reason.CONFIGURATION, exception.reason());
    }
}

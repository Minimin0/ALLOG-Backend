package com.allog.verification.storage.s3;

import com.allog.verification.storage.VerificationMediaStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "ALLOG_S3_TEST_ENDPOINT", matches = ".+")
class S3VerificationMediaStorageIntegrationTest {

    private static final String BUCKET = "allog-verification-integration";
    private static final String KEY = "verification-media/integration";
    private static final byte[] CONTENT = {1, 2, 3, 4, 5, 6};

    private static S3Client s3Client;
    private static S3Presigner presigner;
    private static S3VerificationMediaStorage storage;

    @BeforeAll
    static void setUp() {
        URI endpoint = URI.create(System.getenv("ALLOG_S3_TEST_ENDPOINT"));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                System.getenv("ALLOG_S3_TEST_ACCESS_KEY"),
                System.getenv("ALLOG_S3_TEST_SECRET_KEY")
        ));
        s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(KEY)
                        .contentType("video/mp4")
                        .build(),
                RequestBody.fromBytes(CONTENT)
        );
        storage = new S3VerificationMediaStorage(s3Client, presigner, BUCKET, Clock.systemUTC());
    }

    @AfterAll
    static void tearDown() {
        if (s3Client != null) {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
            s3Client.close();
        }
        if (presigner != null) {
            presigner.close();
        }
    }

    @Test
    void acquiresPrivateObjectAndEnforcesBoundedReadAgainstS3CompatibleStorage() {
        VerificationMediaStorage.StoredMedia complete = storage.acquire(KEY, CONTENT.length);
        VerificationMediaStorage.StoredMedia bounded = storage.acquire(KEY, 3);
        VerificationMediaStorage.StorageException missing = assertThrows(
                VerificationMediaStorage.StorageException.class,
                () -> storage.acquire("verification-media/missing", CONTENT.length)
        );

        assertEquals(CONTENT.length, complete.contentLength());
        assertEquals("video/mp4", complete.contentType());
        assertArrayEquals(CONTENT, complete.content());
        assertEquals(4, bounded.bodyLength());
        assertEquals(VerificationMediaStorage.StorageException.Reason.NOT_FOUND, missing.reason());
    }
}

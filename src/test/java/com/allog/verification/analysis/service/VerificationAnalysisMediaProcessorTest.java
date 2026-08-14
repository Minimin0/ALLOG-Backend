package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.media.TestPhotos;
import com.allog.verification.storage.VerificationMediaStorage;
import com.allog.verification.template.VerificationTemplateCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationAnalysisMediaProcessorTest {

    private static final VerificationAnalysisClaim CLAIM = new VerificationAnalysisClaim(
            10L,
            UUID.randomUUID(),
            1
    );
    private static final byte[] GPS_TAGS = "GPSLatitude=37.5665".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PHOTO = TestPhotos.jpeg(4, 4);
    private static final VerificationAnalysisInput INPUT = new VerificationAnalysisInput(
            CLAIM.analysisId(),
            CLAIM.analysisRequestId(),
            CLAIM.attemptCount(),
            VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1,
            20L,
            "verification-media/test",
            "image/jpeg",
            PHOTO.length
    );
    private static final VerificationMediaStorage.StoredMedia MEDIA =
            new VerificationMediaStorage.StoredMedia(
                    INPUT.objectKey(),
                    INPUT.sizeBytes(),
                    INPUT.contentType(),
                    PHOTO
            );
    private static final VerificationTemplateCatalog CATALOG = new VerificationTemplateCatalog();
    private static final VerificationCriteria CRITERIA =
            CATALOG.requireCriteria(VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1);
    private static final VerificationAnalysisProvider.Result PROVIDER_RESULT = providerResult();

    @Mock
    private VerificationAnalysisInputLoader inputLoader;

    @Mock
    private VerificationMediaStorage storage;

    @Mock
    private VerificationAnalysisProvider provider;

    @Test
    void loadsAcquiresValidatesAndCallsProviderWithMinimizedEvidenceOutsideTransaction() {
        when(inputLoader.load(CLAIM)).thenReturn(INPUT);
        when(storage.acquire(INPUT.objectKey(), INPUT.sizeBytes())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return MEDIA;
        });
        when(provider.analyze(eq(CRITERIA.providerContract()), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            VerificationAnalysisProvider.Evidence evidence = invocation.getArgument(1);
            assertEquals(VerificationCriteria.MediaModality.PHOTO, evidence.modality());
            assertEquals("image/jpeg", evidence.contentType());
            assertArrayEquals(PHOTO, evidence.content());
            return PROVIDER_RESULT;
        });

        assertEquals(
                new VerificationAnalysisMediaProcessor.Observed(CRITERIA, PROVIDER_RESULT),
                processor().process(CLAIM)
        );
        verify(provider).analyze(eq(CRITERIA.providerContract()), any());
    }

    @Test
    void providerBoundBytesCarryNoExifGpsMetadata() {
        byte[] tagged = TestPhotos.withApp1(PHOTO, GPS_TAGS);
        VerificationAnalysisInput taggedInput = new VerificationAnalysisInput(
                INPUT.analysisId(),
                INPUT.analysisRequestId(),
                INPUT.attemptCount(),
                INPUT.criteriaReference(),
                INPUT.verificationId(),
                INPUT.objectKey(),
                INPUT.contentType(),
                tagged.length
        );
        when(inputLoader.load(CLAIM)).thenReturn(taggedInput);
        when(storage.acquire(taggedInput.objectKey(), taggedInput.sizeBytes()))
                .thenReturn(new VerificationMediaStorage.StoredMedia(
                        taggedInput.objectKey(),
                        taggedInput.sizeBytes(),
                        taggedInput.contentType(),
                        tagged
                ));
        when(provider.analyze(any(), any())).thenAnswer(invocation -> {
            VerificationAnalysisProvider.Evidence evidence = invocation.getArgument(1);
            assertTrue(contains(tagged, GPS_TAGS), "fixture must actually carry GPS tags");
            assertFalse(contains(evidence.content(), GPS_TAGS), "provider must never receive GPS metadata");
            assertArrayEquals(PHOTO, evidence.content());
            return PROVIDER_RESULT;
        });

        assertEquals(
                new VerificationAnalysisMediaProcessor.Observed(CRITERIA, PROVIDER_RESULT),
                processor().process(CLAIM)
        );
    }

    @Test
    void mapsUnsanitizablePhotoToBadRequestWithoutProviderCall() {
        VerificationAnalysisInput corruptedInput = new VerificationAnalysisInput(
                INPUT.analysisId(),
                INPUT.analysisRequestId(),
                INPUT.attemptCount(),
                INPUT.criteriaReference(),
                INPUT.verificationId(),
                INPUT.objectKey(),
                INPUT.contentType(),
                4
        );
        when(inputLoader.load(CLAIM)).thenReturn(corruptedInput);
        when(storage.acquire(corruptedInput.objectKey(), corruptedInput.sizeBytes()))
                .thenReturn(new VerificationMediaStorage.StoredMedia(
                        corruptedInput.objectKey(),
                        corruptedInput.sizeBytes(),
                        corruptedInput.contentType(),
                        new byte[]{1, 2, 3, 4}
                ));

        assertFailure(VerificationAnalysisFailureCode.BAD_REQUEST, processor().process(CLAIM));
        verify(provider, never()).analyze(any(), any());
    }

    @Test
    void mapsInvalidOrUnsupportedMediaToBadRequestWithoutProviderCall() {
        VerificationAnalysisInput videoInput = new VerificationAnalysisInput(
                CLAIM.analysisId(),
                CLAIM.analysisRequestId(),
                CLAIM.attemptCount(),
                INPUT.criteriaReference(),
                INPUT.verificationId(),
                INPUT.objectKey(),
                "video/mp4",
                INPUT.sizeBytes()
        );
        when(inputLoader.load(CLAIM)).thenReturn(INPUT).thenReturn(videoInput);
        when(storage.acquire(INPUT.objectKey(), INPUT.sizeBytes()))
                .thenReturn(new VerificationMediaStorage.StoredMedia(
                        INPUT.objectKey(),
                        INPUT.sizeBytes() + 1,
                        INPUT.contentType(),
                        Arrays.copyOf(PHOTO, PHOTO.length + 1)
                ))
                .thenReturn(new VerificationMediaStorage.StoredMedia(
                        videoInput.objectKey(),
                        videoInput.sizeBytes(),
                        videoInput.contentType(),
                        PHOTO
                ));

        assertFailure(VerificationAnalysisFailureCode.BAD_REQUEST, processor().process(CLAIM));
        assertFailure(VerificationAnalysisFailureCode.BAD_REQUEST, processor().process(CLAIM));
        verify(provider, never()).analyze(any(), any());
    }

    @Test
    void rejectsUnknownExactEnqueueProvenance() {
        when(inputLoader.load(CLAIM)).thenReturn(new VerificationAnalysisInput(
                INPUT.analysisId(),
                INPUT.analysisRequestId(),
                INPUT.attemptCount(),
                new VerificationCriteria.Reference("unknown", 1),
                INPUT.verificationId(),
                INPUT.objectKey(),
                INPUT.contentType(),
                INPUT.sizeBytes()
        ));

        assertFailure(VerificationAnalysisFailureCode.BAD_REQUEST, processor().process(CLAIM));
        verify(storage, never()).acquire(any(), anyLong());
        verify(provider, never()).analyze(any(), any());
    }

    @Test
    void inputReferenceSelectsExactCatalogVersionWithoutCallerCriteria() {
        when(inputLoader.load(CLAIM)).thenReturn(INPUT);
        when(storage.acquire(INPUT.objectKey(), INPUT.sizeBytes())).thenReturn(MEDIA);
        when(provider.analyze(any(), any())).thenReturn(PROVIDER_RESULT);

        VerificationAnalysisMediaProcessor.Observed observed =
                (VerificationAnalysisMediaProcessor.Observed) processor().process(CLAIM);

        assertEquals(VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1, observed.criteria().reference());
        verify(provider).analyze(eq(CRITERIA.providerContract()), any());
    }

    @Test
    void noLatestFallbackExistsForUnknownFutureVersion() {
        when(inputLoader.load(CLAIM)).thenReturn(new VerificationAnalysisInput(
                INPUT.analysisId(),
                INPUT.analysisRequestId(),
                INPUT.attemptCount(),
                new VerificationCriteria.Reference("meal-photo-record", 2),
                INPUT.verificationId(),
                INPUT.objectKey(),
                INPUT.contentType(),
                INPUT.sizeBytes()
        ));

        assertFailure(VerificationAnalysisFailureCode.BAD_REQUEST, processor().process(CLAIM));
        verify(storage, never()).acquire(any(), anyLong());
        verify(provider, never()).analyze(any(), any());
    }

    @Test
    void mapsUnavailableStorageToNetwork() {
        when(inputLoader.load(CLAIM)).thenReturn(INPUT);
        when(storage.acquire(INPUT.objectKey(), INPUT.sizeBytes()))
                .thenThrow(new VerificationMediaStorage.StorageException(
                        VerificationMediaStorage.StorageException.Reason.UNAVAILABLE,
                        "unavailable"
                ));

        assertFailure(VerificationAnalysisFailureCode.NETWORK, processor().process(CLAIM));
        verify(provider, never()).analyze(any(), any());
    }

    @Test
    void rejectsCompleteObservationMissingCriteriaRequiredValues() {
        when(inputLoader.load(CLAIM)).thenReturn(INPUT);
        when(storage.acquire(INPUT.objectKey(), INPUT.sizeBytes())).thenReturn(MEDIA);
        when(provider.analyze(any(), any())).thenReturn(new VerificationAnalysisProvider.Result(
                "synthetic-model",
                new VerificationAnalysisObservation(
                        true,
                        null,
                        false,
                        true,
                        VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
                )
        ));

        assertFailure(
                VerificationAnalysisFailureCode.INVALID_RESPONSE,
                processor().process(CLAIM)
        );
    }

    @Test
    void mapsClassifiedProviderFailuresToTheirFailureCode() {
        when(inputLoader.load(CLAIM)).thenReturn(INPUT);
        when(storage.acquire(INPUT.objectKey(), INPUT.sizeBytes())).thenReturn(MEDIA);
        when(provider.analyze(any(), any()))
                .thenThrow(new VerificationAnalysisProvider.ProviderException(
                        VerificationAnalysisFailureCode.RATE_LIMITED,
                        "rate limited"
                ))
                .thenThrow(new VerificationAnalysisProvider.ProviderException(
                        VerificationAnalysisFailureCode.INTERRUPTED,
                        "interrupted"
                ));

        assertFailure(VerificationAnalysisFailureCode.RATE_LIMITED, processor().process(CLAIM));
        assertFailure(VerificationAnalysisFailureCode.INTERRUPTED, processor().process(CLAIM));
    }

    @Test
    void doesNotGuessMissingConfigurationOrUnexpectedProviderFailures() {
        VerificationMediaStorage.StorageException missing = new VerificationMediaStorage.StorageException(
                VerificationMediaStorage.StorageException.Reason.NOT_FOUND,
                "missing"
        );
        VerificationMediaStorage.StorageException configuration = new VerificationMediaStorage.StorageException(
                VerificationMediaStorage.StorageException.Reason.CONFIGURATION,
                "configuration"
        );
        when(inputLoader.load(CLAIM)).thenReturn(INPUT);
        when(storage.acquire(INPUT.objectKey(), INPUT.sizeBytes()))
                .thenThrow(missing)
                .thenThrow(configuration)
                .thenReturn(MEDIA);
        when(provider.analyze(any(), any())).thenThrow(new IllegalStateException("unexpected"));

        assertEquals(missing, assertThrows(
                VerificationMediaStorage.StorageException.class,
                () -> processor().process(CLAIM)
        ));
        assertEquals(configuration, assertThrows(
                VerificationMediaStorage.StorageException.class,
                () -> processor().process(CLAIM)
        ));
        assertThrows(IllegalStateException.class, () -> processor().process(CLAIM));
    }

    @Test
    void rejectsExecutionInsideCallerTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> processor().process(CLAIM));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private VerificationAnalysisMediaProcessor processor() {
        return new VerificationAnalysisMediaProcessor(inputLoader, storage, provider, CATALOG);
    }

    private void assertFailure(
            VerificationAnalysisFailureCode expected,
            VerificationAnalysisMediaProcessor.Outcome outcome
    ) {
        VerificationAnalysisMediaProcessor.Failure failure =
                (VerificationAnalysisMediaProcessor.Failure) outcome;
        assertEquals(expected, failure.failureCode());
    }

    /** ISO-8859-1 maps every byte 1:1, so this is an exact byte-subsequence search. */
    private static boolean contains(byte[] haystack, byte[] needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1)
                .contains(new String(needle, StandardCharsets.ISO_8859_1));
    }

    private static VerificationAnalysisProvider.Result providerResult() {
        return new VerificationAnalysisProvider.Result(
                "synthetic-model",
                new VerificationAnalysisObservation(
                        true,
                        new BigDecimal("0.7500"),
                        false,
                        true,
                        VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
                )
        );
    }
}

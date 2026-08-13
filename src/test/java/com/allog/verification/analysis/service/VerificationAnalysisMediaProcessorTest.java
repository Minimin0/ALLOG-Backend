package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.storage.VerificationMediaStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
    private static final VerificationAnalysisInput INPUT = new VerificationAnalysisInput(
            CLAIM.analysisId(),
            CLAIM.analysisRequestId(),
            CLAIM.attemptCount(),
            20L,
            "verification-media/test",
            "video/mp4",
            4
    );
    private static final VerificationMediaStorage.StoredMedia MEDIA =
            new VerificationMediaStorage.StoredMedia(
                    INPUT.objectKey(),
                    INPUT.sizeBytes(),
                    INPUT.contentType(),
                    new byte[]{1, 2, 3, 4}
            );
    private static final VerificationCriteria CRITERIA = criteria(VerificationCriteria.MediaModality.VIDEO);
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
            assertEquals(VerificationCriteria.MediaModality.VIDEO, evidence.modality());
            assertEquals("video/mp4", evidence.contentType());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, evidence.content());
            return PROVIDER_RESULT;
        });

        assertEquals(
                new VerificationAnalysisMediaProcessor.Observed(CRITERIA, PROVIDER_RESULT),
                processor().process(CLAIM, CRITERIA)
        );
        verify(provider).analyze(eq(CRITERIA.providerContract()), any());
    }

    @Test
    void mapsInvalidOrUnsupportedMediaToBadRequestWithoutProviderCall() {
        when(inputLoader.load(CLAIM)).thenReturn(INPUT);
        when(storage.acquire(INPUT.objectKey(), INPUT.sizeBytes()))
                .thenReturn(new VerificationMediaStorage.StoredMedia(
                        INPUT.objectKey(),
                        INPUT.sizeBytes() + 1,
                        INPUT.contentType(),
                        new byte[]{1, 2, 3, 4, 5}
                ))
                .thenReturn(MEDIA);

        assertFailure(VerificationAnalysisFailureCode.BAD_REQUEST, processor().process(CLAIM, CRITERIA));
        assertFailure(
                VerificationAnalysisFailureCode.BAD_REQUEST,
                processor().process(CLAIM, criteria(VerificationCriteria.MediaModality.PHOTO))
        );
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

        assertFailure(VerificationAnalysisFailureCode.NETWORK, processor().process(CLAIM, CRITERIA));
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
                processor().process(CLAIM, CRITERIA)
        );
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
                () -> processor().process(CLAIM, CRITERIA)
        ));
        assertEquals(configuration, assertThrows(
                VerificationMediaStorage.StorageException.class,
                () -> processor().process(CLAIM, CRITERIA)
        ));
        assertThrows(IllegalStateException.class, () -> processor().process(CLAIM, CRITERIA));
    }

    @Test
    void rejectsExecutionInsideCallerTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> processor().process(CLAIM, CRITERIA));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private VerificationAnalysisMediaProcessor processor() {
        return new VerificationAnalysisMediaProcessor(inputLoader, storage, provider);
    }

    private void assertFailure(
            VerificationAnalysisFailureCode expected,
            VerificationAnalysisMediaProcessor.Outcome outcome
    ) {
        VerificationAnalysisMediaProcessor.Failure failure =
                (VerificationAnalysisMediaProcessor.Failure) outcome;
        assertEquals(expected, failure.failureCode());
    }

    private static VerificationCriteria criteria(VerificationCriteria.MediaModality modality) {
        return new VerificationCriteria(
                new VerificationCriteria.Reference("TEST_EVIDENCE", 1),
                20L,
                Set.of(modality),
                Set.of(
                        VerificationCriteria.ObservationType.TARGET_EVIDENCE_VISIBLE,
                        VerificationCriteria.ObservationType.CRITERIA_RELEVANCE_SCORE,
                        VerificationCriteria.ObservationType.INTEGRITY_ANOMALY,
                        VerificationCriteria.ObservationType.FRAMING_SUFFICIENCY
                ),
                "Test-only evidence requirements"
        );
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

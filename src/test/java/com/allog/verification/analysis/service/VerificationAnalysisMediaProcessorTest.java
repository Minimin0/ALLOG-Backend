package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.storage.VerificationMediaStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Mock
    private VerificationAnalysisInputLoader inputLoader;

    @Mock
    private VerificationMediaStorage storage;

    @Mock
    private VerificationAnalysisProvider provider;

    @Test
    void loadsAcquiresValidatesAndCallsProviderWithoutTransaction() {
        VerificationAnalysisProcessor.Outcome expected = new VerificationAnalysisProcessor.Success(successResult());
        when(inputLoader.load(CLAIM)).thenReturn(INPUT);
        when(storage.acquire(INPUT.objectKey(), INPUT.sizeBytes())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return MEDIA;
        });
        when(provider.analyze(INPUT, MEDIA)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return expected;
        });

        assertEquals(expected, processor().process(CLAIM));
        verify(provider).analyze(INPUT, MEDIA);
    }

    @Test
    void mapsInvalidMediaToBadRequestWithoutProviderCall() {
        when(inputLoader.load(CLAIM)).thenReturn(INPUT);
        when(storage.acquire(INPUT.objectKey(), INPUT.sizeBytes()))
                .thenReturn(new VerificationMediaStorage.StoredMedia(
                        INPUT.objectKey(),
                        INPUT.sizeBytes() + 1,
                        INPUT.contentType(),
                        new byte[]{1, 2, 3, 4, 5}
                ));

        assertFailure(VerificationAnalysisFailureCode.BAD_REQUEST, processor().process(CLAIM));
        verify(provider, never()).analyze(INPUT, MEDIA);
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
        verify(provider, never()).analyze(INPUT, MEDIA);
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
        when(provider.analyze(INPUT, MEDIA)).thenThrow(new IllegalStateException("unexpected"));

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
        return new VerificationAnalysisMediaProcessor(inputLoader, storage, provider);
    }

    private void assertFailure(
            VerificationAnalysisFailureCode expected,
            VerificationAnalysisProcessor.Outcome outcome
    ) {
        VerificationAnalysisProcessor.Failure failure = (VerificationAnalysisProcessor.Failure) outcome;
        assertEquals(expected, failure.failureCode());
    }

    private VerificationAnalysisSuccessResult successResult() {
        return new VerificationAnalysisSuccessResult(
                AnalysisRecommendation.PASS,
                "synthetic-reason",
                "synthetic-model",
                "synthetic-criteria",
                true,
                new BigDecimal("0.7500"),
                false,
                true
        );
    }
}

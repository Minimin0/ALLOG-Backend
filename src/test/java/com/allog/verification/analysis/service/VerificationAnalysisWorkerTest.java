package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationAnalysisWorkerTest {

    private static final VerificationAnalysisClaim CLAIM = new VerificationAnalysisClaim(
            10L,
            UUID.randomUUID(),
            1
    );

    @Mock
    private VerificationAnalysisClaimService claimService;

    @Mock
    private VerificationAnalysisResultService resultService;

    @Mock
    private VerificationAnalysisProcessor processor;

    @Test
    void unavailableProcessorDoesNotClaim() {
        VerificationAnalysisWorker worker = new VerificationAnalysisWorker(
                claimService,
                resultService,
                Optional.empty()
        );

        assertEquals(
                VerificationAnalysisWorker.ExecutionResult.PROCESSOR_UNAVAILABLE,
                worker.processNext()
        );
        verifyNoInteractions(claimService, resultService);
    }

    @Test
    void noClaimIsNormalNoWork() {
        when(claimService.claimNextPending()).thenReturn(Optional.empty());

        assertEquals(VerificationAnalysisWorker.ExecutionResult.NO_WORK, worker().processNext());
        verifyNoInteractions(processor, resultService);
    }

    @Test
    void successOutcomeCompletesOnceOutsideWorkerTransaction() {
        VerificationAnalysisSuccessResult result = successResult();
        when(claimService.claimNextPending()).thenReturn(Optional.of(CLAIM));
        when(processor.process(CLAIM)).thenReturn(new VerificationAnalysisProcessor.Success(result));
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        }).when(resultService).completeSuccess(CLAIM, result);

        assertEquals(VerificationAnalysisWorker.ExecutionResult.COMPLETED, worker().processNext());
        verify(processor).process(CLAIM);
        verify(resultService).completeSuccess(CLAIM, result);
        verify(resultService, never()).completeFailure(CLAIM, VerificationAnalysisFailureCode.TIMEOUT);
    }

    @Test
    void failureOutcomePersistsProcessorClassificationWithoutRetry() {
        when(claimService.claimNextPending()).thenReturn(Optional.of(CLAIM));
        when(processor.process(CLAIM)).thenReturn(new VerificationAnalysisProcessor.Failure(
                VerificationAnalysisFailureCode.TIMEOUT
        ));
        when(resultService.completeFailure(CLAIM, VerificationAnalysisFailureCode.TIMEOUT)).thenReturn(true);

        assertEquals(VerificationAnalysisWorker.ExecutionResult.COMPLETED, worker().processNext());
        verify(processor).process(CLAIM);
        verify(resultService).completeFailure(CLAIM, VerificationAnalysisFailureCode.TIMEOUT);
    }

    @Test
    void rejectedFencedResultIsNotRetried() {
        VerificationAnalysisSuccessResult result = successResult();
        when(claimService.claimNextPending()).thenReturn(Optional.of(CLAIM));
        when(processor.process(CLAIM)).thenReturn(new VerificationAnalysisProcessor.Success(result));
        when(resultService.completeSuccess(CLAIM, result)).thenReturn(false);

        assertEquals(
                VerificationAnalysisWorker.ExecutionResult.STALE_RESULT_REJECTED,
                worker().processNext()
        );
        verify(resultService).completeSuccess(CLAIM, result);
    }

    @Test
    void unexpectedProcessorExceptionLeavesResultPersistenceUntouched() {
        when(claimService.claimNextPending()).thenReturn(Optional.of(CLAIM));
        when(processor.process(CLAIM)).thenThrow(new IllegalStateException("synthetic failure"));

        assertEquals(
                VerificationAnalysisWorker.ExecutionResult.PROCESSOR_EXCEPTION,
                worker().processNext()
        );
        verifyNoInteractions(resultService);
    }

    private VerificationAnalysisWorker worker() {
        return new VerificationAnalysisWorker(claimService, resultService, Optional.of(processor));
    }

    private VerificationAnalysisSuccessResult successResult() {
        return new VerificationAnalysisSuccessResult(
                AnalysisRecommendation.PASS,
                new VerificationCriteria.Reference("TEST_EVIDENCE", 1),
                new VerificationAnalysisProvider.Result(
                        "synthetic-model",
                        new VerificationAnalysisObservation(
                                true,
                                new BigDecimal("0.7500"),
                                false,
                                true,
                                VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
                        )
                )
        );
    }
}

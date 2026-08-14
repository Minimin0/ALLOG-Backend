package com.allog.verification.analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
public class VerificationAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(VerificationAnalysisWorker.class);

    private final VerificationAnalysisClaimService claimService;
    private final VerificationAnalysisResultService resultService;
    private final Optional<VerificationAnalysisProcessor> processor;

    public VerificationAnalysisWorker(
            VerificationAnalysisClaimService claimService,
            VerificationAnalysisResultService resultService,
            Optional<VerificationAnalysisProcessor> processor
    ) {
        this.claimService = Objects.requireNonNull(claimService);
        this.resultService = Objects.requireNonNull(resultService);
        this.processor = Objects.requireNonNull(processor);
    }

    public ExecutionResult processNext() {
        if (processor.isEmpty()) {
            return ExecutionResult.PROCESSOR_UNAVAILABLE;
        }

        Optional<VerificationAnalysisClaim> claimed = claimService.claimNextPending();
        if (claimed.isEmpty()) {
            return ExecutionResult.NO_WORK;
        }

        VerificationAnalysisClaim claim = claimed.get();
        VerificationAnalysisProcessor.Outcome outcome;
        try {
            outcome = Objects.requireNonNull(
                    processor.get().process(claim),
                    "processor outcome must not be null"
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Verification analysis processor failed: exception={}",
                    exception.getClass().getSimpleName()
            );
            return ExecutionResult.PROCESSOR_EXCEPTION;
        }

        boolean completed;
        if (outcome instanceof VerificationAnalysisProcessor.Success success) {
            completed = resultService.completeSuccess(claim, success.result());
        } else {
            VerificationAnalysisProcessor.Failure failure = (VerificationAnalysisProcessor.Failure) outcome;
            completed = resultService.completeFailure(claim, failure.failureCode());
        }
        return completed ? ExecutionResult.COMPLETED : ExecutionResult.STALE_RESULT_REJECTED;
    }

    public enum ExecutionResult {
        PROCESSOR_UNAVAILABLE,
        NO_WORK,
        COMPLETED,
        STALE_RESULT_REJECTED,
        PROCESSOR_EXCEPTION
    }
}

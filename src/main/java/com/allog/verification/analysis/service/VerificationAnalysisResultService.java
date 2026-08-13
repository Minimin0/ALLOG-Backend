package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysis;
import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationAnalysisStatus;
import com.allog.verification.analysis.repository.VerificationAnalysisRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Service
public class VerificationAnalysisResultService {

    private final VerificationAnalysisRepository repository;
    private final Clock clock;

    public VerificationAnalysisResultService(VerificationAnalysisRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public boolean completeSuccess(
            VerificationAnalysisClaim claim,
            VerificationAnalysisSuccessResult result
    ) {
        Objects.requireNonNull(result, "result must not be null");
        VerificationAnalysis analysis = currentAttemptForUpdate(claim);
        if (analysis == null) {
            return false;
        }

        Instant completedAt = VerificationAnalysisTime.snapshot(clock);
        analysis.succeed(
                result.recommendation(),
                result.reasonCode(),
                result.providerModel(),
                result.criteriaVersion(),
                result.objectPresence(),
                result.relevanceScore(),
                result.anomalyDetected(),
                result.framedProperly(),
                completedAt
        );
        return true;
    }

    @Transactional
    public boolean completeFailure(
            VerificationAnalysisClaim claim,
            VerificationAnalysisFailureCode failureCode
    ) {
        Objects.requireNonNull(failureCode, "failureCode must not be null");
        VerificationAnalysis analysis = currentAttemptForUpdate(claim);
        if (analysis == null) {
            return false;
        }

        analysis.fail(failureCode, VerificationAnalysisTime.snapshot(clock));
        return true;
    }

    private VerificationAnalysis currentAttemptForUpdate(VerificationAnalysisClaim claim) {
        Objects.requireNonNull(claim, "claim must not be null");
        VerificationAnalysis analysis = repository.findByIdForUpdate(claim.analysisId()).orElse(null);
        if (analysis == null
                || analysis.getStatus() != VerificationAnalysisStatus.PROCESSING
                || analysis.getAttemptCount() != claim.attemptCount()
                || !analysis.getAnalysisRequestId().equals(claim.analysisRequestId())) {
            return null;
        }
        return analysis;
    }
}

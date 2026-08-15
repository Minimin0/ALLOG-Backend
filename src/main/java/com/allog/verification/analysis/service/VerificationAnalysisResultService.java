package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysis;
import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationAnalysisStatus;
import com.allog.reward.service.VerificationRewardService;
import com.allog.verification.analysis.repository.VerificationAnalysisRepository;
import com.allog.verification.domain.Verification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Service
public class VerificationAnalysisResultService {

    private final VerificationAnalysisRepository repository;
    private final VerificationRewardService rewardService;
    private final Clock clock;

    public VerificationAnalysisResultService(
            VerificationAnalysisRepository repository,
            VerificationRewardService rewardService,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.rewardService = Objects.requireNonNull(rewardService);
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
        var providerResult = result.providerResult();
        var observation = providerResult.observation();
        analysis.succeed(
                result.criteriaReference(),
                result.recommendation(),
                observation.reasonCode().name(),
                providerResult.providerModel(),
                observation.objectPresence(),
                observation.relevanceScore(),
                observation.anomalyDetected(),
                observation.framedProperly(),
                completedAt
        );
        Verification verification = analysis.getVerification();
        if (result.recommendation() == AnalysisRecommendation.PASS) {
            verification.approve(clock);
            rewardService.grantFor(verification);
        } else if (result.recommendation() == AnalysisRecommendation.REJECT_CANDIDATE) {
            // One guided retry, then it stops being the member's problem and becomes a hold.
            if (verification.hasRetryRemaining()) {
                verification.requestRetry();
            } else {
                verification.requestReview();
            }
        }
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

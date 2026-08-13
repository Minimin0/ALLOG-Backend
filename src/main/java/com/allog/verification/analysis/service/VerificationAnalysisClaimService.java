package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysis;
import com.allog.verification.analysis.domain.VerificationAnalysisStatus;
import com.allog.verification.analysis.repository.VerificationAnalysisRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class VerificationAnalysisClaimService {

    private static final PageRequest SINGLE_CANDIDATE = PageRequest.of(0, 1);

    private final VerificationAnalysisRepository repository;
    private final Clock clock;
    private final Duration processingTimeout;

    public VerificationAnalysisClaimService(
            VerificationAnalysisRepository repository,
            Clock clock,
            @Value("${allog.verification.analysis.processing-timeout:PT5M}") Duration processingTimeout
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.processingTimeout = requirePositive(processingTimeout);
    }

    @Transactional
    public Optional<VerificationAnalysisClaim> claimNextPending() {
        Instant startedAt = snapshotNow();
        List<Long> candidateIds = repository.findPendingCandidateIds(
                VerificationAnalysisStatus.PENDING,
                SINGLE_CANDIDATE
        );
        if (candidateIds.isEmpty()) {
            return Optional.empty();
        }

        VerificationAnalysis analysis = repository.findByIdForUpdate(candidateIds.getFirst()).orElse(null);
        if (analysis == null || analysis.getStatus() != VerificationAnalysisStatus.PENDING) {
            return Optional.empty();
        }
        analysis.startProcessing(startedAt);
        return Optional.of(VerificationAnalysisClaim.from(analysis));
    }

    @Transactional
    public boolean recoverNextStaleProcessing() {
        Instant staleAtOrBefore = snapshotNow().minus(processingTimeout);
        List<Long> candidateIds = repository.findStaleProcessingCandidateIds(
                VerificationAnalysisStatus.PROCESSING,
                staleAtOrBefore,
                SINGLE_CANDIDATE
        );
        if (candidateIds.isEmpty()) {
            return false;
        }

        VerificationAnalysis analysis = repository.findByIdForUpdate(candidateIds.getFirst()).orElse(null);
        if (analysis == null
                || analysis.getStatus() != VerificationAnalysisStatus.PROCESSING
                || !analysis.isRetryEligible(staleAtOrBefore)) {
            return false;
        }
        analysis.recoverForRetry(staleAtOrBefore);
        return true;
    }

    private Instant snapshotNow() {
        return Objects.requireNonNull(clock.instant(), "clock instant must not be null")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "processingTimeout must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("processingTimeout must be positive");
        }
        return value;
    }
}

package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysis;
import com.allog.verification.analysis.domain.VerificationAnalysisStatus;
import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.analysis.repository.VerificationAnalysisRepository;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationMedia;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.repository.VerificationMediaRepository;
import com.allog.verification.storage.VerificationMediaProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Service
public class VerificationAnalysisInputLoader {

    private final VerificationAnalysisRepository analysisRepository;
    private final VerificationMediaRepository mediaRepository;
    private final VerificationMediaProperties mediaProperties;

    public VerificationAnalysisInputLoader(
            VerificationAnalysisRepository analysisRepository,
            VerificationMediaRepository mediaRepository,
            VerificationMediaProperties mediaProperties
    ) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.mediaRepository = Objects.requireNonNull(mediaRepository);
        this.mediaProperties = Objects.requireNonNull(mediaProperties);
    }

    @Transactional(readOnly = true)
    public VerificationAnalysisInput load(VerificationAnalysisClaim claim) {
        Objects.requireNonNull(claim, "claim must not be null");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("analysis input load requires a transaction");
        }

        VerificationAnalysis analysis = analysisRepository.findByIdWithVerification(claim.analysisId())
                .orElseThrow(() -> failure(Reason.ANALYSIS_NOT_FOUND));
        if (analysis.getStatus() != VerificationAnalysisStatus.PROCESSING
                || analysis.getAttemptCount() != claim.attemptCount()
                || !analysis.getAnalysisRequestId().equals(claim.analysisRequestId())) {
            throw failure(Reason.STALE_CLAIM);
        }

        Verification verification = analysis.getVerification();
        if (verification.getStatus() != VerificationStatus.SUBMITTED) {
            throw failure(Reason.INVALID_VERIFICATION);
        }
        if (analysis.getCriteriaVersion() == null) {
            throw failure(Reason.MISSING_CRITERIA);
        }
        final VerificationCriteria.Reference criteriaReference;
        try {
            criteriaReference = VerificationCriteria.Reference.fromStorageValue(analysis.getCriteriaVersion());
        } catch (IllegalArgumentException exception) {
            throw failure(Reason.INVALID_CRITERIA);
        }
        VerificationMedia media = mediaRepository.findByVerification_Id(verification.getId())
                .orElseThrow(() -> failure(Reason.MISSING_MEDIA));
        if (!media.isConfirmed()) {
            throw failure(Reason.UNCONFIRMED_MEDIA);
        }

        Long confirmedSize = media.getConfirmedSizeBytes();
        String contentType;
        try {
            contentType = VerificationMediaProperties.normalizeContentType(media.getContentType());
        } catch (IllegalArgumentException exception) {
            throw failure(Reason.INVALID_MEDIA);
        }
        if (!mediaProperties.enabled()
                || confirmedSize == null
                || confirmedSize <= 0
                || confirmedSize > media.getExpectedSizeBytes()
                || confirmedSize > mediaProperties.maxBytes()
                || confirmedSize >= Integer.MAX_VALUE
                || !mediaProperties.allowedContentTypes().contains(contentType)) {
            throw failure(Reason.INVALID_MEDIA);
        }

        try {
            return new VerificationAnalysisInput(
                    analysis.getId(),
                    analysis.getAnalysisRequestId(),
                    analysis.getAttemptCount(),
                    criteriaReference,
                    verification.getId(),
                    media.getObjectKey(),
                    contentType,
                    confirmedSize
            );
        } catch (IllegalArgumentException exception) {
            throw failure(Reason.INVALID_MEDIA);
        }
    }

    private LoadException failure(Reason reason) {
        return new LoadException(reason);
    }

    public enum Reason {
        ANALYSIS_NOT_FOUND,
        STALE_CLAIM,
        INVALID_VERIFICATION,
        MISSING_CRITERIA,
        INVALID_CRITERIA,
        MISSING_MEDIA,
        UNCONFIRMED_MEDIA,
        INVALID_MEDIA
    }

    public static final class LoadException extends RuntimeException {

        private final Reason reason;

        private LoadException(Reason reason) {
            super("verification analysis input is unavailable: " + reason);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}

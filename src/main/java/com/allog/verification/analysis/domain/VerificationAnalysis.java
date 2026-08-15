package com.allog.verification.analysis.domain;

import com.allog.common.persistence.BaseTimeEntity;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "verification_analysis",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_verification_analysis_verification",
                        columnNames = "verification_id"
                ),
                @UniqueConstraint(
                        name = "uk_verification_analysis_request",
                        columnNames = "analysis_request_id"
                )
        },
        indexes = @Index(
                name = "idx_verification_analysis_poll",
                columnList = "status,last_attempt_at,id"
        )
)
public class VerificationAnalysis extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "verification_id", nullable = false)
    private Verification verification;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "analysis_request_id", nullable = false, columnDefinition = "CHAR(36)")
    private UUID analysisRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VerificationAnalysisStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AnalysisRecommendation recommendation;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "provider_model", length = 100)
    private String providerModel;

    @Column(name = "criteria_version", length = 64)
    private String criteriaVersion;

    @Column(name = "object_presence")
    private Boolean objectPresence;

    @Column(name = "relevance_score", precision = 5, scale = 4)
    private BigDecimal relevanceScore;

    @Column(name = "anomaly_detected")
    private Boolean anomalyDetected;

    @Column(name = "framed_properly")
    private Boolean framedProperly;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 32)
    private VerificationAnalysisFailureCode failureCode;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected VerificationAnalysis() {
    }

    private VerificationAnalysis(Verification verification, UUID analysisRequestId, String criteriaReference) {
        this.verification = Objects.requireNonNull(verification, "verification must not be null");
        this.analysisRequestId = Objects.requireNonNull(analysisRequestId, "analysisRequestId must not be null");
        if (verification.getStatus() != VerificationStatus.SUBMITTED) {
            throw new IllegalStateException("analysis requires a SUBMITTED verification");
        }
        this.criteriaVersion = criteriaReference;
        this.status = VerificationAnalysisStatus.PENDING;
        this.attemptCount = 0;
    }

    public static VerificationAnalysis createPending(Verification verification, UUID analysisRequestId) {
        return new VerificationAnalysis(verification, analysisRequestId, null);
    }

    public static VerificationAnalysis createPending(
            Verification verification,
            UUID analysisRequestId,
            VerificationCriteria criteria
    ) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        var group = Objects.requireNonNull(verification, "verification must not be null")
                .getRoutineSchedule()
                .getRoutineGroup();
        if (!group.hasVerificationBinding()) {
            throw new IllegalStateException("criteria-aware analysis requires a group verification binding");
        }
        if (!group.getVerificationTemplateKey().equals(criteria.templateKey())
                || !group.getVerificationCriteriaReference().equals(criteria.reference())) {
            throw new IllegalArgumentException("criteria is not the exact reference pinned by the verification group");
        }
        return new VerificationAnalysis(verification, analysisRequestId, criteria.reference().storageValue());
    }

    public void startProcessing(Instant startedAt) {
        if (status != VerificationAnalysisStatus.PENDING) {
            throw new IllegalStateException("only PENDING analysis can start processing");
        }
        Instant requiredStartedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        if (lastAttemptAt != null && requiredStartedAt.isBefore(lastAttemptAt)) {
            throw new IllegalStateException("lastAttemptAt must not move backwards");
        }
        int nextAttemptCount = Math.incrementExact(attemptCount);
        this.status = VerificationAnalysisStatus.PROCESSING;
        this.attemptCount = nextAttemptCount;
        this.lastAttemptAt = requiredStartedAt;
    }

    public void recoverForRetry(Instant staleAtOrBefore) {
        if (!isRetryEligible(staleAtOrBefore) || status != VerificationAnalysisStatus.PROCESSING) {
            throw new IllegalStateException("only stale PROCESSING analysis can be recovered");
        }
        this.status = VerificationAnalysisStatus.PENDING;
    }

    public boolean isRetryEligible(Instant staleAtOrBefore) {
        Objects.requireNonNull(staleAtOrBefore, "staleAtOrBefore must not be null");
        if (status == VerificationAnalysisStatus.PENDING) {
            return true;
        }
        return status == VerificationAnalysisStatus.PROCESSING
                && lastAttemptAt != null
                && !lastAttemptAt.isAfter(staleAtOrBefore);
    }

    public void succeed(
            VerificationCriteria.Reference expectedCriteriaReference,
            AnalysisRecommendation recommendation,
            String reasonCode,
            String providerModel,
            Boolean objectPresence,
            BigDecimal relevanceScore,
            Boolean anomalyDetected,
            Boolean framedProperly,
            Instant completedAt
    ) {
        requireProcessingCompletion(completedAt);
        requireCriteriaReference(expectedCriteriaReference);
        AnalysisRecommendation requiredRecommendation = Objects.requireNonNull(
                recommendation,
                "recommendation must not be null"
        );
        requireRelevanceScore(relevanceScore);
        this.status = VerificationAnalysisStatus.SUCCEEDED;
        this.recommendation = requiredRecommendation;
        this.reasonCode = reasonCode;
        this.providerModel = providerModel;
        this.objectPresence = objectPresence;
        this.relevanceScore = relevanceScore;
        this.anomalyDetected = anomalyDetected;
        this.framedProperly = framedProperly;
        this.failureCode = null;
        this.completedAt = completedAt;
    }

    public void fail(VerificationAnalysisFailureCode failureCode, Instant completedAt) {
        requireProcessingCompletion(completedAt);
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
        this.status = VerificationAnalysisStatus.FAILED;
        this.recommendation = null;
        this.completedAt = completedAt;
    }

    /**
     * Re-queues this analysis for the guided retry's submission. The criteria reference is deliberately
     * left untouched: a retry is judged against the same criteria version as the attempt it replaces.
     */
    public void rearmForRetry(UUID analysisRequestId) {
        if (status != VerificationAnalysisStatus.SUCCEEDED) {
            throw new IllegalStateException("only a completed analysis can be re-armed for a retry");
        }
        this.analysisRequestId = Objects.requireNonNull(analysisRequestId, "analysisRequestId must not be null");
        this.status = VerificationAnalysisStatus.PENDING;
        this.recommendation = null;
        this.reasonCode = null;
        this.providerModel = null;
        this.objectPresence = null;
        this.relevanceScore = null;
        this.anomalyDetected = null;
        this.framedProperly = null;
        this.failureCode = null;
        this.attemptCount = 0;
        this.lastAttemptAt = null;
        this.completedAt = null;
    }

    public Long getId() {
        return id;
    }

    public Verification getVerification() {
        return verification;
    }

    public UUID getAnalysisRequestId() {
        return analysisRequestId;
    }

    public VerificationAnalysisStatus getStatus() {
        return status;
    }

    public AnalysisRecommendation getRecommendation() {
        return recommendation;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getProviderModel() {
        return providerModel;
    }

    public String getCriteriaVersion() {
        return criteriaVersion;
    }

    public Boolean getObjectPresence() {
        return objectPresence;
    }

    public BigDecimal getRelevanceScore() {
        return relevanceScore;
    }

    public Boolean getAnomalyDetected() {
        return anomalyDetected;
    }

    public Boolean getFramedProperly() {
        return framedProperly;
    }

    public VerificationAnalysisFailureCode getFailureCode() {
        return failureCode;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    private void requireProcessingCompletion(Instant completionTime) {
        if (status != VerificationAnalysisStatus.PROCESSING || attemptCount <= 0 || lastAttemptAt == null) {
            throw new IllegalStateException("only an active PROCESSING attempt can complete");
        }
        Objects.requireNonNull(completionTime, "completedAt must not be null");
        if (completionTime.isBefore(lastAttemptAt)) {
            throw new IllegalStateException("completedAt must not be before lastAttemptAt");
        }
    }

    private void requireRelevanceScore(BigDecimal score) {
        if (score != null && (score.signum() < 0 || score.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("relevanceScore must be between 0 and 1");
        }
    }

    private void requireCriteriaReference(VerificationCriteria.Reference expected) {
        String expectedValue = Objects.requireNonNull(
                expected,
                "expectedCriteriaReference must not be null"
        ).storageValue();
        if (criteriaVersion == null) {
            throw new IllegalStateException("analysis has no enqueue-bound criteria reference");
        }
        if (!criteriaVersion.equals(expectedValue)) {
            throw new IllegalArgumentException("analysis criteria reference does not match the processing result");
        }
    }
}

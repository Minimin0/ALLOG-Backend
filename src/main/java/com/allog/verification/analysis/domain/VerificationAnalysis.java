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

    private VerificationAnalysis(Verification verification, UUID analysisRequestId) {
        this.verification = Objects.requireNonNull(verification, "verification must not be null");
        this.analysisRequestId = Objects.requireNonNull(analysisRequestId, "analysisRequestId must not be null");
        if (verification.getStatus() != VerificationStatus.SUBMITTED) {
            throw new IllegalStateException("analysis requires a SUBMITTED verification");
        }
        this.status = VerificationAnalysisStatus.PENDING;
        this.attemptCount = 0;
    }

    public static VerificationAnalysis createPending(Verification verification, UUID analysisRequestId) {
        return new VerificationAnalysis(verification, analysisRequestId);
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
}

package com.allog.verification.repository;

import com.allog.verification.analysis.domain.AnalysisRecommendation;

import java.time.Instant;
import java.time.LocalDate;

/** One held verification with everything a person needs to judge it, gathered in a single query. */
public record PendingReviewRow(
        Long verificationId,
        Long userId,
        Long groupId,
        LocalDate scheduledDate,
        int attemptCount,
        Instant createdAt,
        Instant submittedAt,
        String mediaObjectKey,
        AnalysisRecommendation recommendation,
        String reasonCode,
        Boolean objectPresence,
        Boolean anomalyDetected,
        String criteriaVersion
) {
}

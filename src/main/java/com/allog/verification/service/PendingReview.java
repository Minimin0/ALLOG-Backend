package com.allog.verification.service;

import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.repository.PendingReviewRow;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;

/** A held verification as an operator sees it, with a link they can actually open. */
public record PendingReview(
        Long verificationId,
        Long userId,
        Long groupId,
        LocalDate scheduledDate,
        int attemptCount,
        Instant createdAt,
        Instant submittedAt,
        URI mediaUrl,
        AnalysisRecommendation recommendation,
        String reasonCode,
        Boolean objectPresence,
        Boolean anomalyDetected,
        String criteriaVersion
) {

    static PendingReview from(PendingReviewRow row, URI mediaUrl) {
        return new PendingReview(
                row.verificationId(),
                row.userId(),
                row.groupId(),
                row.scheduledDate(),
                row.attemptCount(),
                row.createdAt(),
                row.submittedAt(),
                mediaUrl,
                row.recommendation(),
                row.reasonCode(),
                row.objectPresence(),
                row.anomalyDetected(),
                row.criteriaVersion()
        );
    }
}

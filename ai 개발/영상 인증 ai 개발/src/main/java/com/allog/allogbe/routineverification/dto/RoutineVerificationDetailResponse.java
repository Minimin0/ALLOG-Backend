package com.allog.allogbe.routineverification.dto;

import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.ReviewPriority;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.SubmissionType;

import java.time.LocalDateTime;

public record RoutineVerificationDetailResponse(
		Long id,
		Long userId,
		Long challengeId,
		Long participationId,
		SubmissionType submissionType,
		String mediaUrl,
		String capturedFrameUrl,
		LocalDateTime submittedAt,
		MetadataCheckResponse metadataCheck,
		VisionAnalysisResponse visionAnalysis,
		QualityCheckResponse qualityCheck,
		AiClassification aiClassification,
		ReviewStatus reviewStatus,
		ReviewPriority reviewPriority,
		boolean countedInScore,
		LocalDateTime reviewedAt,
		Long reviewedBy
) {
}

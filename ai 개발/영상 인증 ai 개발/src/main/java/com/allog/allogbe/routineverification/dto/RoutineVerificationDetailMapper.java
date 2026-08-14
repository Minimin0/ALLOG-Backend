package com.allog.allogbe.routineverification.dto;

import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.domain.QualityCheck;
import com.allog.allogbe.routineverification.domain.RoutineVerification;
import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;

public final class RoutineVerificationDetailMapper {

	private RoutineVerificationDetailMapper() {
	}

	public static RoutineVerificationDetailResponse toResponse(RoutineVerification verification) {
		return new RoutineVerificationDetailResponse(
				verification.getId(),
				verification.getUserId(),
				verification.getChallengeId(),
				verification.getParticipationId(),
				verification.getSubmissionType(),
				verification.getMediaUrl(),
				verification.getCapturedFrameUrl(),
				verification.getSubmittedAt(),
				toMetadataCheckResponse(verification.getMetadataCheck()),
				toVisionAnalysisResponse(verification.getVisionAnalysis()),
				toQualityCheckResponse(verification.getQualityCheck()),
				verification.getAiClassification(),
				verification.getReviewStatus(),
				verification.getReviewPriority(),
				verification.isCountedInScore(),
				verification.getReviewedAt(),
				verification.getReviewedBy());
	}

	public static RoutineVerificationQueueItemResponse toQueueItem(RoutineVerification verification) {
		return new RoutineVerificationQueueItemResponse(
				verification.getId(),
				verification.getUserId(),
				verification.getChallengeId(),
				verification.getAiClassification(),
				verification.getReviewPriority(),
				verification.getSubmittedAt());
	}

	private static MetadataCheckResponse toMetadataCheckResponse(MetadataCheck metadataCheck) {
		if (metadataCheck == null) {
			return null;
		}
		return new MetadataCheckResponse(
				metadataCheck.isWithinTimeWindow(), metadataCheck.isDuplicate(), metadataCheck.getDuplicateOfId());
	}

	private static QualityCheckResponse toQualityCheckResponse(QualityCheck qualityCheck) {
		if (qualityCheck == null) {
			return null;
		}
		return new QualityCheckResponse(
				qualityCheck.getBlurScore(), qualityCheck.isBlurry(),
				qualityCheck.getResolutionWidth(), qualityCheck.getResolutionHeight(),
				qualityCheck.isPassesMinResolution(), qualityCheck.isFramedProperly(), qualityCheck.getFramingIssue());
	}

	private static VisionAnalysisResponse toVisionAnalysisResponse(VisionAnalysisResult vision) {
		if (vision == null) {
			return null;
		}
		return new VisionAnalysisResponse(
				vision.getObjectPresence(), vision.getDetectedObjects(), vision.getRelevanceScore(),
				vision.getAnomalyFlags(), vision.getConfidence(), vision.getSummary());
	}
}

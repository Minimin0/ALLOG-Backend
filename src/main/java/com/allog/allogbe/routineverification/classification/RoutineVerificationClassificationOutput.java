package com.allog.allogbe.routineverification.classification;

import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;

public record RoutineVerificationClassificationOutput(
		MetadataCheck metadataCheck,
		VisionAnalysisResult visionAnalysis,
		ClassificationDecision decision
) {
}

package com.allog.allogbe.routineverification.dto;

import java.util.List;

public record VisionAnalysisResponse(
		Boolean objectPresence,
		List<String> detectedObjects,
		Double relevanceScore,
		List<String> anomalyFlags,
		Double confidence,
		String summary
) {
}

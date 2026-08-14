package com.allog.allogbe.routineverification.vision;

import java.util.List;

public record VisionAnalysisRequest(
		byte[] imageBytes,
		String imageMediaType,
		ChallengeCategory category,
		String routineDescription,
		List<String> expectedObjects
) {
}

package com.allog.allogbe.routineverification.vision;

import java.util.List;

public record ChallengeVisionContext(
		ChallengeCategory category,
		String routineDescription,
		List<String> expectedObjects
) {
}

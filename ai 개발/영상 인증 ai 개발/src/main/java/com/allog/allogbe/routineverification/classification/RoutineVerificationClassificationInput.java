package com.allog.allogbe.routineverification.classification;

import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitRequest;
import com.allog.allogbe.routineverification.vision.ChallengeCategory;

import java.awt.image.BufferedImage;
import java.util.List;

public record RoutineVerificationClassificationInput(
		RoutineVerificationSubmitRequest submitRequest,
		BufferedImage image,
		String imageMediaType,
		ChallengeCategory category,
		String routineDescription,
		List<String> expectedObjects
) {
}

package com.allog.allogbe.routineverification.classification;

import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.ReviewPriority;
import com.allog.allogbe.routineverification.domain.ReviewStatus;

public record ClassificationDecision(
		AiClassification aiClassification,
		ReviewStatus reviewStatus,
		ReviewPriority reviewPriority,
		boolean countedInScore
) {
}

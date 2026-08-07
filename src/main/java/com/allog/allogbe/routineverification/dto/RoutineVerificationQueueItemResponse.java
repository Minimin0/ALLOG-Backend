package com.allog.allogbe.routineverification.dto;

import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.ReviewPriority;

import java.time.LocalDateTime;

public record RoutineVerificationQueueItemResponse(
		Long id,
		Long userId,
		Long challengeId,
		AiClassification aiClassification,
		ReviewPriority reviewPriority,
		LocalDateTime submittedAt
) {
}

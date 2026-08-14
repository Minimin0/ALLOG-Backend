package com.allog.allogbe.routineverification.dto;

import com.allog.allogbe.routineverification.domain.ReviewStatus;

public record RoutineVerificationSubmitResponse(
		Long verificationId,
		ReviewStatus reviewStatus,
		String message
) {
}

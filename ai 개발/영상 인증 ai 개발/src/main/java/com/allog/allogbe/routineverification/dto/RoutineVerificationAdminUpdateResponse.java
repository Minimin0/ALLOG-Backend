package com.allog.allogbe.routineverification.dto;

import com.allog.allogbe.routineverification.domain.ReviewStatus;

import java.time.LocalDateTime;

public record RoutineVerificationAdminUpdateResponse(
		Long id,
		ReviewStatus reviewStatus,
		LocalDateTime reviewedAt,
		Long reviewedBy
) {
}

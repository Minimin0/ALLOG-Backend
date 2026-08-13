package com.allog.allogbe.routineverification.dto;

public record QualityCheckResponse(
		Float blurScore,
		boolean isBlurry,
		Integer resolutionWidth,
		Integer resolutionHeight,
		boolean passesMinResolution,
		Boolean isFramedProperly,
		String framingIssue
) {
}

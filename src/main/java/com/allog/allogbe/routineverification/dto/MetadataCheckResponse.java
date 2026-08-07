package com.allog.allogbe.routineverification.dto;

public record MetadataCheckResponse(
		boolean isWithinTimeWindow,
		boolean isDuplicate,
		Long duplicateOfId
) {
}

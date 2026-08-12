package com.allog.allogbe.routineverification.duplicate;

public record DuplicateCheckResult(boolean duplicate, Long duplicateOfId) {

	public static DuplicateCheckResult duplicate(Long duplicateOfId) {
		return new DuplicateCheckResult(true, duplicateOfId);
	}

	public static DuplicateCheckResult notDuplicate() {
		return new DuplicateCheckResult(false, null);
	}
}

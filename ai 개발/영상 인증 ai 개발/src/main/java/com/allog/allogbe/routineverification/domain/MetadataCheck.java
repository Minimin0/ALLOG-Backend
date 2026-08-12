package com.allog.allogbe.routineverification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 비전 API와 독립적으로 계산되는 룰 기반 체크 결과.
 * isDuplicate/duplicateOfId 는 STAGE5(중복 탐지)에서 채워진다 — STAGE3 게이트 통과 시점에는 false/null.
 */
@Embeddable
public class MetadataCheck {

	@Column(name = "is_within_time_window", nullable = false)
	private boolean withinTimeWindow;

	@Column(name = "is_duplicate", nullable = false)
	private boolean duplicate;

	@Column(name = "duplicate_of_id")
	private Long duplicateOfId;

	protected MetadataCheck() {
	}

	public MetadataCheck(boolean withinTimeWindow, boolean duplicate, Long duplicateOfId) {
		this.withinTimeWindow = withinTimeWindow;
		this.duplicate = duplicate;
		this.duplicateOfId = duplicateOfId;
	}

	public boolean isWithinTimeWindow() {
		return withinTimeWindow;
	}

	public boolean isDuplicate() {
		return duplicate;
	}

	public Long getDuplicateOfId() {
		return duplicateOfId;
	}
}

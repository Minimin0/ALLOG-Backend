package com.allog.allogbe.routineverification.domain;

/**
 * PENDING -> AUTO_VALID | FLAGGED_FOR_REVIEW 까지는 AI/룰 엔진이 전환한다.
 * VALID_CONFIRMED / INVALIDATED / RESUBMIT_REQUESTED 는 상호신고 + 운영자 검토를 거쳐야만 전환 가능하며,
 * 이 도메인 계층에서 AI 응답만으로 직접 전환해서는 안 된다.
 */
public enum ReviewStatus {
	PENDING,
	AUTO_VALID,
	FLAGGED_FOR_REVIEW,
	VALID_CONFIRMED,
	INVALIDATED,
	RESUBMIT_REQUESTED
}

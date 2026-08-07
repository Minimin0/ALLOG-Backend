package com.allog.allogbe.routineverification.domain;

/**
 * STAGE7 규칙엔진이 metadataCheck + visionAnalysis 를 결합해 산출하는 "제안" 값이다.
 * AI/룰 엔진은 이 값을 근거로 reviewStatus 를 PENDING -> AUTO_VALID / FLAGGED_FOR_REVIEW 까지만 전환하며,
 * 최종 무효화(REJECT_CANDIDATE -> INVALIDATED)는 상호신고 + 운영자 검토를 거쳐야 확정된다.
 */
public enum AiClassification {
	PASS,
	REVIEW_REQUIRED,
	REJECT_CANDIDATE
}

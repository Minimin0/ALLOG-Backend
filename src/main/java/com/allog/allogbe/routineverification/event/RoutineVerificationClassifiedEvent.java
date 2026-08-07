package com.allog.allogbe.routineverification.event;

import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.ReviewStatus;

import java.time.LocalDateTime;

/**
 * STAGE7 규칙엔진의 1차 분류가 끝날 때마다 발행되는 이벤트.
 * ④ 진행 페이스 코칭(다른 담당자 영역)이 사용자의 최근 인증 흐름(예: 연속 REJECT_CANDIDATE 등)을
 * 참고할 수 있도록 노출하는 조회용 신호이며, 코칭 로직 자체는 이 모듈에서 구현하지 않는다.
 */
public record RoutineVerificationClassifiedEvent(
		Long verificationId,
		Long userId,
		Long challengeId,
		AiClassification aiClassification,
		ReviewStatus reviewStatus,
		LocalDateTime occurredAt
) {
}

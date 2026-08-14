package com.allog.allogbe.routineverification.insight;

import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.ReviewStatus;

import java.time.LocalDateTime;

/** ④ 진행 페이스 코칭 등 외부(다른 담당자 영역) 조회 전용 읽기 모델. */
public record RoutineVerificationSummary(
		Long verificationId,
		Long challengeId,
		AiClassification aiClassification,
		ReviewStatus reviewStatus,
		boolean countedInScore,
		LocalDateTime submittedAt
) {
}

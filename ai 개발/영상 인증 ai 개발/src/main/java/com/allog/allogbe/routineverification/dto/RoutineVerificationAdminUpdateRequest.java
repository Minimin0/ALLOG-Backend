package com.allog.allogbe.routineverification.dto;

import com.allog.allogbe.routineverification.domain.ReviewStatus;

/**
 * reviewedBy 는 임시로 요청 바디에서 받는다 — 실제로는 운영자 인증 주체에서 가져와야 한다
 * (연동 필요 지점, 인증/인가 도메인 없음).
 */
public record RoutineVerificationAdminUpdateRequest(
		ReviewStatus targetStatus,
		Long reviewedBy,
		String reason
) {
}

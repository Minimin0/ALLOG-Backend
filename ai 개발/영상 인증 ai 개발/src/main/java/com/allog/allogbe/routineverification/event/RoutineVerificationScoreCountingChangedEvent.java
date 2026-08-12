package com.allog.allogbe.routineverification.event;

import java.time.LocalDateTime;

/**
 * isCountedInScore 값이 실제로 바뀔 때(false->true 즉시반영, 또는 운영자 확정으로 true<->false)
 * 발행되는 이벤트. 달성률(개인/그룹) 계산 서비스가 이 이벤트를 구독해 완주 집계를 갱신하는 지점이다
 * (연동 필요 지점 — 이 모듈은 이벤트를 "발행"만 하고, 실제 달성률 계산/하트·포인트 지급 로직은
 * 기존(또는 향후) 서비스가 구독해서 처리해야 하며 여기서는 구현하지 않는다).
 */
public record RoutineVerificationScoreCountingChangedEvent(
		Long verificationId,
		Long userId,
		Long challengeId,
		Long participationId,
		boolean countedInScore,
		LocalDateTime occurredAt
) {
}

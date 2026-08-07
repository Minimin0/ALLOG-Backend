package com.allog.allogbe.routineverification.vision;

/**
 * 연동 필요 지점: Challenge/ChallengeTemplate 도메인(다른 담당자 구현 예정)에서 카테고리/루틴 설명/
 * 기대 객체 목록을 조회하는 구현체로 교체되어야 한다. 이번 스코프에서는 인터페이스만 정의한다.
 */
public interface ChallengeVisionContextProvider {

	ChallengeVisionContext getContext(Long challengeId);
}

package com.allog.allogbe.routineverification.policy;

/**
 * 연동 필요 지점: Challenge/ChallengeTemplate 도메인(다른 담당자 구현 예정)에서
 * 실제 정책을 조회하는 구현체로 교체되어야 한다. 이번 스코프(③ 인증 1차 분석)에서는
 * 인터페이스만 정의하고 구현하지 않는다.
 */
public interface ChallengeVerificationPolicyProvider {

	ChallengeVerificationPolicy getPolicy(Long challengeId);
}

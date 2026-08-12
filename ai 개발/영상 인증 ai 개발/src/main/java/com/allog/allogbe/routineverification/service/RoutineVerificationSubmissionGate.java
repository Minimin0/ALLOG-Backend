package com.allog.allogbe.routineverification.service;

import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitRequest;
import com.allog.allogbe.routineverification.exception.DisallowedSubmissionTypeException;
import com.allog.allogbe.routineverification.exception.OutsideVerificationTimeWindowException;
import com.allog.allogbe.routineverification.policy.ChallengeVerificationPolicy;
import com.allog.allogbe.routineverification.policy.ChallengeVerificationPolicyProvider;
import org.springframework.stereotype.Component;

/**
 * 제출 검증 게이트 (STAGE3). AI/비전 분석 이전 단계의 순수 규칙 기반 1차 관문이며,
 * 아래 두 조건 중 하나라도 위반하면 저장 없이 즉시 예외로 거부한다.
 *  1) 챌린지 템플릿이 허용하는 submissionType 인지
 *  2) 제출 시각이 인증 가능 시간 범위 내인지 (isWithinTimeWindow)
 *
 * 중복 여부(isDuplicate)는 STAGE5 책임이므로 이 게이트는 항상 false/null 로 채운 MetadataCheck 를 반환한다.
 */
@Component
public class RoutineVerificationSubmissionGate {

	private final ChallengeVerificationPolicyProvider policyProvider;

	public RoutineVerificationSubmissionGate(ChallengeVerificationPolicyProvider policyProvider) {
		this.policyProvider = policyProvider;
	}

	public MetadataCheck validate(RoutineVerificationSubmitRequest request) {
		ChallengeVerificationPolicy policy = policyProvider.getPolicy(request.challengeId());

		if (!policy.allows(request.submissionType())) {
			throw new DisallowedSubmissionTypeException(
					"챌린지가 허용하지 않는 제출 방식입니다: " + request.submissionType());
		}

		if (!policy.isWithinWindow(request.submittedAt())) {
			throw new OutsideVerificationTimeWindowException(
					"인증 가능 시간(%s~%s) 범위를 벗어났습니다.".formatted(
							policy.verificationWindowStart(), policy.verificationWindowEnd()));
		}

		return new MetadataCheck(true, false, null);
	}
}

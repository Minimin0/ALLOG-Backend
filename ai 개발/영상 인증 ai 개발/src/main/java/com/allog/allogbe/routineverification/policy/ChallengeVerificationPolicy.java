package com.allog.allogbe.routineverification.policy;

import com.allog.allogbe.routineverification.domain.SubmissionType;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * 챌린지 템플릿이 허용하는 제출 방식 + 하루 중 인증 가능 시간대(daily recurring window).
 * 실제 값의 출처는 Challenge/ChallengeTemplate 도메인이며(미구현, 연동 필요 지점),
 * 이 레코드는 STAGE3 게이트가 의존하는 최소 계약(contract)이다.
 */
public record ChallengeVerificationPolicy(
		Set<SubmissionType> allowedSubmissionTypes,
		LocalTime verificationWindowStart,
		LocalTime verificationWindowEnd
) {

	public boolean allows(SubmissionType type) {
		return allowedSubmissionTypes.contains(type);
	}

	/**
	 * 자정을 넘기는 시간대(예: 22:00~02:00, 수면 챌린지)도 지원한다.
	 * 경계값(시작/종료 시각 자체)은 포함(inclusive)한다.
	 */
	public boolean isWithinWindow(LocalDateTime submittedAt) {
		LocalTime time = submittedAt.toLocalTime();
		boolean sameOrAfterStart = !time.isBefore(verificationWindowStart);
		boolean sameOrBeforeEnd = !time.isAfter(verificationWindowEnd);

		if (!verificationWindowStart.isAfter(verificationWindowEnd)) {
			return sameOrAfterStart && sameOrBeforeEnd;
		}
		return sameOrAfterStart || sameOrBeforeEnd;
	}
}

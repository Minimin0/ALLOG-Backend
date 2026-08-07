package com.allog.allogbe.routineverification.duplicate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 중복/도용 비교 대상 이력 조회 포트. 실제 구현은 RoutineVerificationRepository 를 사용해
 * DUPLICATE_CHECK_WINDOW 기간 내 제출을 조회한다 (STAGE6/7 파이프라인 배선 시 연결).
 */
public interface SubmissionHashHistoryProvider {

	/** 동일 유저가 같은 챌린지에 최근 제출한 이력 (재제출/자기 복제 탐지용). */
	List<HashedSubmission> findRecentSubmissionsBySameUser(Long userId, Long challengeId, LocalDateTime since);

	/** 같은 챌린지/그룹 내 다른 유저의 최근 제출 이력 (도용 탐지용). */
	List<HashedSubmission> findOtherUsersSubmissionsInChallenge(Long challengeId, Long excludingUserId, LocalDateTime since);
}

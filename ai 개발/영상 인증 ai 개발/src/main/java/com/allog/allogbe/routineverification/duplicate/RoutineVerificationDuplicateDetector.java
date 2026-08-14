package com.allog.allogbe.routineverification.duplicate;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 중복/도용 탐지 (STAGE5). 비교 대상은 두 그룹:
 *  1) 동일 유저의 최근 제출 이력 (재사용/재제출)
 *  2) 동일 챌린지/그룹 내 다른 유저의 최근 제출 이력 (도용)
 * 임계치 이상 유사하면 isDuplicate=true 로 판단하며, 최종 판단은 STAGE7 규칙엔진이
 * REJECT_CANDIDATE 로 매핑한다 — 이 클래스는 최종 무효화 권한이 없다.
 */
@Component
public class RoutineVerificationDuplicateDetector {

	/**
	 * pHash 64bit 중 해밍 거리 임계치. phash.org 등에서 널리 쓰이는 경험적 기준(<=10, 전체 비트의 약 15.6%)을
	 * 채택했다. 실제 제출 사진 데이터가 쌓이기 전까지는 검증되지 않은 초기값이며, STAGE7 운영 데이터로
	 * 재보정이 필요하다 (연동 필요 지점: 운영 모니터링/튜닝 프로세스는 이번 스코프 밖).
	 */
	static final int HAMMING_DISTANCE_THRESHOLD = 10;

	/** 비교 대상 조회 기간. 매직 넘버 방지를 위해 상수로 분리. */
	static final Duration DUPLICATE_CHECK_WINDOW = Duration.ofDays(7);

	private final SubmissionHashHistoryProvider historyProvider;

	public RoutineVerificationDuplicateDetector(SubmissionHashHistoryProvider historyProvider) {
		this.historyProvider = historyProvider;
	}

	public DuplicateCheckResult detect(Long userId, Long challengeId, PerceptualHash targetHash,
			LocalDateTime submittedAt) {
		LocalDateTime since = submittedAt.minus(DUPLICATE_CHECK_WINDOW);

		List<HashedSubmission> ownHistory =
				historyProvider.findRecentSubmissionsBySameUser(userId, challengeId, since);
		Optional<HashedSubmission> selfMatch = findMatch(targetHash, ownHistory);
		if (selfMatch.isPresent()) {
			return DuplicateCheckResult.duplicate(selfMatch.get().verificationId());
		}

		List<HashedSubmission> othersHistory =
				historyProvider.findOtherUsersSubmissionsInChallenge(challengeId, userId, since);
		Optional<HashedSubmission> theftMatch = findMatch(targetHash, othersHistory);
		if (theftMatch.isPresent()) {
			return DuplicateCheckResult.duplicate(theftMatch.get().verificationId());
		}

		return DuplicateCheckResult.notDuplicate();
	}

	private Optional<HashedSubmission> findMatch(PerceptualHash target, List<HashedSubmission> candidates) {
		return candidates.stream()
				.filter(candidate -> target.hammingDistance(candidate.hash()) <= HAMMING_DISTANCE_THRESHOLD)
				.findFirst();
	}
}

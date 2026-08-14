package com.allog.allogbe.routineverification.duplicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutineVerificationDuplicateDetectorTest {

	private static final Long USER_ID = 100L;
	private static final Long CHALLENGE_ID = 1L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 8, 9, 0);

	@Mock
	private SubmissionHashHistoryProvider historyProvider;

	private RoutineVerificationDuplicateDetector detector;

	@BeforeEach
	void setUp() {
		detector = new RoutineVerificationDuplicateDetector(historyProvider);
		lenient().when(historyProvider.findRecentSubmissionsBySameUser(anyLong(), anyLong(), any()))
				.thenReturn(List.of());
		lenient().when(historyProvider.findOtherUsersSubmissionsInChallenge(anyLong(), anyLong(), any()))
				.thenReturn(List.of());
	}

	@Test
	void 유사한_이력이_전혀_없으면_중복이_아니다() {
		PerceptualHash target = new PerceptualHash(0b1010_1010L);

		DuplicateCheckResult result = detector.detect(USER_ID, CHALLENGE_ID, target, NOW);

		assertThat(result.duplicate()).isFalse();
		assertThat(result.duplicateOfId()).isNull();
	}

	@Test
	void 본인의_과거_제출과_해시가_같으면_자기중복으로_판단한다() {
		PerceptualHash target = new PerceptualHash(0b1111_0000L);
		HashedSubmission ownPast = new HashedSubmission(500L, USER_ID, new PerceptualHash(0b1111_0000L));

		when(historyProvider.findRecentSubmissionsBySameUser(USER_ID, CHALLENGE_ID, NOW.minusDays(7)))
				.thenReturn(List.of(ownPast));

		DuplicateCheckResult result = detector.detect(USER_ID, CHALLENGE_ID, target, NOW);

		assertThat(result.duplicate()).isTrue();
		assertThat(result.duplicateOfId()).isEqualTo(500L);
	}

	@Test
	void 타유저의_제출과_유사하면_도용으로_판단한다() {
		PerceptualHash target = new PerceptualHash(0b1111_0000L);
		Long otherUserId = 200L;
		// 해밍 거리 1 (임계치 10 이하) -> 도용으로 탐지되어야 함
		HashedSubmission otherUserSubmission =
				new HashedSubmission(600L, otherUserId, new PerceptualHash(0b1111_0001L));

		when(historyProvider.findOtherUsersSubmissionsInChallenge(CHALLENGE_ID, USER_ID, NOW.minusDays(7)))
				.thenReturn(List.of(otherUserSubmission));

		DuplicateCheckResult result = detector.detect(USER_ID, CHALLENGE_ID, target, NOW);

		assertThat(result.duplicate()).isTrue();
		assertThat(result.duplicateOfId()).isEqualTo(600L);
	}

	@Test
	void 자기_이력을_우선_확인하고_일치하면_타유저_이력은_조회하되_먼저_찾은_결과를_반환한다() {
		PerceptualHash target = new PerceptualHash(0b1111_0000L);
		HashedSubmission ownPast = new HashedSubmission(500L, USER_ID, new PerceptualHash(0b1111_0000L));

		when(historyProvider.findRecentSubmissionsBySameUser(USER_ID, CHALLENGE_ID, NOW.minusDays(7)))
				.thenReturn(List.of(ownPast));

		DuplicateCheckResult result = detector.detect(USER_ID, CHALLENGE_ID, target, NOW);

		assertThat(result.duplicateOfId()).isEqualTo(500L);
	}

	@Test
	void 해밍_거리가_임계치를_초과하면_중복이_아니다() {
		// target=0b0000_0000, candidate=0b1111_1111_1 -> 거리 11 (임계치 10 초과)
		PerceptualHash target = new PerceptualHash(0L);
		HashedSubmission farCandidate = new HashedSubmission(700L, USER_ID, new PerceptualHash(0b111_1111_1111L));

		when(historyProvider.findRecentSubmissionsBySameUser(USER_ID, CHALLENGE_ID, NOW.minusDays(7)))
				.thenReturn(List.of(farCandidate));

		DuplicateCheckResult result = detector.detect(USER_ID, CHALLENGE_ID, target, NOW);

		assertThat(result.duplicate()).isFalse();
	}
}

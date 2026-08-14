package com.allog.allogbe.routineverification.e2e;

import com.allog.allogbe.routineverification.duplicate.HashedSubmission;
import com.allog.allogbe.routineverification.duplicate.SubmissionHashHistoryProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** STAGE5 SubmissionHashHistoryProvider(연동 필요 지점)의 통합테스트 전용 대역. 기본값은 이력 없음(중복 없음). */
public class FakeSubmissionHashHistoryProvider implements SubmissionHashHistoryProvider {

	private final List<HashedSubmission> ownHistory = new ArrayList<>();
	private final List<HashedSubmission> othersHistory = new ArrayList<>();

	public void seedOwnHistory(HashedSubmission submission) {
		ownHistory.add(submission);
	}

	public void seedOthersHistory(HashedSubmission submission) {
		othersHistory.add(submission);
	}

	public void reset() {
		ownHistory.clear();
		othersHistory.clear();
	}

	@Override
	public List<HashedSubmission> findRecentSubmissionsBySameUser(Long userId, Long challengeId, LocalDateTime since) {
		return List.copyOf(ownHistory);
	}

	@Override
	public List<HashedSubmission> findOtherUsersSubmissionsInChallenge(Long challengeId, Long excludingUserId,
			LocalDateTime since) {
		return List.copyOf(othersHistory);
	}

	@TestConfiguration
	public static class Config {
		@Bean
		public FakeSubmissionHashHistoryProvider fakeSubmissionHashHistoryProvider() {
			return new FakeSubmissionHashHistoryProvider();
		}
	}
}

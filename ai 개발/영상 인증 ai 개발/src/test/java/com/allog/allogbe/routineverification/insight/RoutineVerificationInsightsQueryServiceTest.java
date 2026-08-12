package com.allog.allogbe.routineverification.insight;

import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.domain.ReviewPriority;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.RoutineVerification;
import com.allog.allogbe.routineverification.domain.SubmissionType;
import com.allog.allogbe.routineverification.repository.RoutineVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutineVerificationInsightsQueryServiceTest {

	@Mock
	private RoutineVerificationRepository repository;

	private RoutineVerificationInsightsQueryService service;

	@BeforeEach
	void setUp() {
		service = new RoutineVerificationInsightsQueryService(repository);
	}

	@Test
	void 최근_제출_이력을_요약_모델로_변환한다() {
		RoutineVerification verification = new RoutineVerification(100L, 1L, 200L, SubmissionType.PHOTO,
				"https://x.jpg", LocalDateTime.of(2026, 8, 8, 8, 0), new MetadataCheck(true, false, null));
		verification.applyClassificationResult(verification.getMetadataCheck(), null,
				AiClassification.PASS, ReviewStatus.AUTO_VALID, ReviewPriority.NORMAL, true);

		when(repository.findTop20ByUserIdOrderBySubmittedAtDesc(100L)).thenReturn(List.of(verification));

		List<RoutineVerificationSummary> summaries = service.findRecentSummaries(100L);

		assertThat(summaries).hasSize(1);
		RoutineVerificationSummary summary = summaries.get(0);
		assertThat(summary.challengeId()).isEqualTo(1L);
		assertThat(summary.aiClassification()).isEqualTo(AiClassification.PASS);
		assertThat(summary.reviewStatus()).isEqualTo(ReviewStatus.AUTO_VALID);
		assertThat(summary.countedInScore()).isTrue();
	}
}

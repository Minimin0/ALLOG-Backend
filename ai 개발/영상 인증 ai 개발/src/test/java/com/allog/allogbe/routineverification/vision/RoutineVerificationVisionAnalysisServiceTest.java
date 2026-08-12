package com.allog.allogbe.routineverification.vision;

import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutineVerificationVisionAnalysisServiceTest {

	@Mock
	private VisionAnalysisClient client;

	private RoutineVerificationVisionAnalysisService service;

	private final VisionAnalysisRequest request = new VisionAnalysisRequest(
			new byte[]{1, 2, 3}, "image/jpeg", ChallengeCategory.EXERCISE, "설명", List.of("운동화"));

	@BeforeEach
	void setUp() {
		service = new RoutineVerificationVisionAnalysisService(client);
	}

	@Test
	void 첫_시도에_성공하면_1회만_호출된다() {
		VisionAnalysisResult expected = new VisionAnalysisResult(
				true, List.of("운동화"), 0.9, List.of(), 0.9, "요약");
		when(client.analyze(any())).thenReturn(expected);

		VisionAnalysisOutcome outcome = service.analyze(request);

		assertThat(outcome.available()).isTrue();
		assertThat(outcome.result()).isEqualTo(expected);
		verify(client, times(1)).analyze(any());
	}

	@Test
	void 두번_실패후_세번째_시도에_성공하면_결과를_반환한다() {
		VisionAnalysisResult expected = new VisionAnalysisResult(
				true, List.of("운동화"), 0.7, List.of(), 0.8, "요약");
		when(client.analyze(any()))
				.thenThrow(new VisionAnalysisAttemptException("일시 오류 1"))
				.thenThrow(new VisionAnalysisAttemptException("일시 오류 2"))
				.thenReturn(expected);

		VisionAnalysisOutcome outcome = service.analyze(request);

		assertThat(outcome.available()).isTrue();
		assertThat(outcome.result()).isEqualTo(expected);
		verify(client, times(3)).analyze(any());
	}

	@Test
	void 세번_모두_실패하면_unavailable을_반환하고_예외를_던지지_않는다() {
		when(client.analyze(any())).thenThrow(new VisionAnalysisAttemptException("영구 오류"));

		VisionAnalysisOutcome outcome = service.analyze(request);

		assertThat(outcome.available()).isFalse();
		assertThat(outcome.result()).isNull();
		verify(client, times(3)).analyze(any());
	}
}

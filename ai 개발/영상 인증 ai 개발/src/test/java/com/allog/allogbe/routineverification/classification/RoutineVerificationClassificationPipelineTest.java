package com.allog.allogbe.routineverification.classification;

import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.domain.ReviewPriority;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.SubmissionType;
import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitRequest;
import com.allog.allogbe.routineverification.duplicate.DuplicateCheckResult;
import com.allog.allogbe.routineverification.duplicate.PerceptualHash;
import com.allog.allogbe.routineverification.duplicate.PerceptualHashCalculator;
import com.allog.allogbe.routineverification.duplicate.RoutineVerificationDuplicateDetector;
import com.allog.allogbe.routineverification.exception.DisallowedSubmissionTypeException;
import com.allog.allogbe.routineverification.exception.OutsideVerificationTimeWindowException;
import com.allog.allogbe.routineverification.service.RoutineVerificationSubmissionGate;
import com.allog.allogbe.routineverification.vision.ChallengeCategory;
import com.allog.allogbe.routineverification.vision.RoutineVerificationVisionAnalysisService;
import com.allog.allogbe.routineverification.vision.VisionAnalysisOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * STAGE7 필수 테스트 케이스 6가지를 그대로 검증한다.
 * ②⑥은 게이트 단계에서 즉시 거부되어 이후 단계(중복/Vision)가 전혀 호출되지 않아야 함을 함께 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RoutineVerificationClassificationPipelineTest {

	@Mock
	private RoutineVerificationSubmissionGate gate;
	@Mock
	private PerceptualHashCalculator hashCalculator;
	@Mock
	private RoutineVerificationDuplicateDetector duplicateDetector;
	@Mock
	private RoutineVerificationVisionAnalysisService visionAnalysisService;

	private RoutineVerificationClassificationPipeline pipeline;

	private final RoutineVerificationSubmitRequest submitRequest = new RoutineVerificationSubmitRequest(
			100L, 1L, 200L, SubmissionType.PHOTO, "https://cdn.allog.dev/x.jpg",
			LocalDateTime.of(2026, 8, 8, 8, 0));

	private final RoutineVerificationClassificationInput input = new RoutineVerificationClassificationInput(
			submitRequest, new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "image/jpeg",
			ChallengeCategory.EXERCISE, "운동 인증", List.of("운동화"));

	@BeforeEach
	void setUp() {
		RoutineVerificationClassificationRuleEngine ruleEngine = new RoutineVerificationClassificationRuleEngine();
		pipeline = new RoutineVerificationClassificationPipeline(
				gate, hashCalculator, duplicateDetector, visionAnalysisService, ruleEngine);
	}

	@Test
	void 케이스1_정상_제출은_PASS다() {
		when(gate.validate(submitRequest)).thenReturn(new MetadataCheck(true, false, null));
		when(hashCalculator.calculate(any())).thenReturn(new PerceptualHash(1L));
		when(duplicateDetector.detect(any(), any(), any(), any())).thenReturn(DuplicateCheckResult.notDuplicate());
		when(visionAnalysisService.analyze(any())).thenReturn(VisionAnalysisOutcome.success(
				new VisionAnalysisResult(true, List.of("운동화"), 0.9, List.of(), 0.9, "운동 중")));

		RoutineVerificationClassificationOutput output = pipeline.process(input);

		assertThat(output.decision().aiClassification()).isEqualTo(AiClassification.PASS);
		assertThat(output.decision().reviewStatus()).isEqualTo(ReviewStatus.AUTO_VALID);
		assertThat(output.decision().countedInScore()).isTrue();
		verify(visionAnalysisService, times(1)).analyze(any());
	}

	@Test
	void 케이스2_시간_외_제출은_거부되고_AI_호출_자체가_발생하지_않는다() {
		when(gate.validate(submitRequest))
				.thenThrow(new OutsideVerificationTimeWindowException("인증 가능 시간 범위를 벗어났습니다."));

		assertThatThrownBy(() -> pipeline.process(input))
				.isInstanceOf(OutsideVerificationTimeWindowException.class);

		verifyNoInteractions(duplicateDetector, visionAnalysisService);
	}

	@Test
	void 케이스3_중복_해시_감지는_REJECT_CANDIDATE_HIGH이고_Vision_호출을_생략한다() {
		when(gate.validate(submitRequest)).thenReturn(new MetadataCheck(true, false, null));
		when(hashCalculator.calculate(any())).thenReturn(new PerceptualHash(1L));
		when(duplicateDetector.detect(any(), any(), any(), any())).thenReturn(DuplicateCheckResult.duplicate(999L));

		RoutineVerificationClassificationOutput output = pipeline.process(input);

		assertThat(output.decision().aiClassification()).isEqualTo(AiClassification.REJECT_CANDIDATE);
		assertThat(output.decision().reviewPriority()).isEqualTo(ReviewPriority.HIGH);
		assertThat(output.metadataCheck().isDuplicate()).isTrue();
		assertThat(output.metadataCheck().getDuplicateOfId()).isEqualTo(999L);
		verify(visionAnalysisService, never()).analyze(any());
	}

	@Test
	void 케이스4_기대_객체_미탐지는_REJECT_CANDIDATE_HIGH다() {
		when(gate.validate(submitRequest)).thenReturn(new MetadataCheck(true, false, null));
		when(hashCalculator.calculate(any())).thenReturn(new PerceptualHash(1L));
		when(duplicateDetector.detect(any(), any(), any(), any())).thenReturn(DuplicateCheckResult.notDuplicate());
		when(visionAnalysisService.analyze(any())).thenReturn(VisionAnalysisOutcome.success(
				new VisionAnalysisResult(false, List.of("고양이"), 0.1, List.of(), 0.8, "운동과 무관한 이미지")));

		RoutineVerificationClassificationOutput output = pipeline.process(input);

		assertThat(output.decision().aiClassification()).isEqualTo(AiClassification.REJECT_CANDIDATE);
		assertThat(output.decision().reviewPriority()).isEqualTo(ReviewPriority.HIGH);
	}

	@Test
	void 케이스5a_이상징후가_있으면_REVIEW_REQUIRED_NORMAL이다() {
		when(gate.validate(submitRequest)).thenReturn(new MetadataCheck(true, false, null));
		when(hashCalculator.calculate(any())).thenReturn(new PerceptualHash(1L));
		when(duplicateDetector.detect(any(), any(), any(), any())).thenReturn(DuplicateCheckResult.notDuplicate());
		when(visionAnalysisService.analyze(any())).thenReturn(VisionAnalysisOutcome.success(
				new VisionAnalysisResult(true, List.of("운동화"), 0.9, List.of("화면 재촬영 의심"), 0.7, "이상 징후 발견")));

		RoutineVerificationClassificationOutput output = pipeline.process(input);

		assertThat(output.decision().aiClassification()).isEqualTo(AiClassification.REVIEW_REQUIRED);
		assertThat(output.decision().reviewPriority()).isEqualTo(ReviewPriority.NORMAL);
	}

	@Test
	void 케이스5b_관련성이_애매하면_REVIEW_REQUIRED_NORMAL이다() {
		when(gate.validate(submitRequest)).thenReturn(new MetadataCheck(true, false, null));
		when(hashCalculator.calculate(any())).thenReturn(new PerceptualHash(1L));
		when(duplicateDetector.detect(any(), any(), any(), any())).thenReturn(DuplicateCheckResult.notDuplicate());
		when(visionAnalysisService.analyze(any())).thenReturn(VisionAnalysisOutcome.success(
				new VisionAnalysisResult(true, List.of("운동화"), 0.3, List.of(), 0.6, "관련성이 애매함")));

		RoutineVerificationClassificationOutput output = pipeline.process(input);

		assertThat(output.decision().aiClassification()).isEqualTo(AiClassification.REVIEW_REQUIRED);
		assertThat(output.decision().reviewPriority()).isEqualTo(ReviewPriority.NORMAL);
	}

	@Test
	void 케이스6_비허용_submissionType은_거부되고_AI_호출_자체가_발생하지_않는다() {
		when(gate.validate(submitRequest))
				.thenThrow(new DisallowedSubmissionTypeException("챌린지가 허용하지 않는 제출 방식입니다."));

		assertThatThrownBy(() -> pipeline.process(input))
				.isInstanceOf(DisallowedSubmissionTypeException.class);

		verifyNoInteractions(duplicateDetector, visionAnalysisService);
	}
}

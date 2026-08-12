package com.allog.allogbe.routineverification.classification;

import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.ReviewPriority;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import com.allog.allogbe.routineverification.vision.VisionAnalysisOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutineVerificationClassificationRuleEngineTest {

	private final RoutineVerificationClassificationRuleEngine ruleEngine =
			new RoutineVerificationClassificationRuleEngine();

	private VisionAnalysisOutcome vision(boolean objectPresence, double relevanceScore, List<String> anomalyFlags) {
		return VisionAnalysisOutcome.success(
				new VisionAnalysisResult(objectPresence, List.of("객체"), relevanceScore, anomalyFlags, 0.9, "요약"));
	}

	@Test
	void 중복이면_Vision_결과와_무관하게_REJECT_CANDIDATE_HIGH이다() {
		ClassificationDecision decision = ruleEngine.classify(true, VisionAnalysisOutcome.unavailable());

		assertThat(decision.aiClassification()).isEqualTo(AiClassification.REJECT_CANDIDATE);
		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.FLAGGED_FOR_REVIEW);
		assertThat(decision.reviewPriority()).isEqualTo(ReviewPriority.HIGH);
		assertThat(decision.countedInScore()).isFalse();
	}

	@Test
	void Vision_API가_재시도_후에도_실패하면_REVIEW_REQUIRED_NORMAL이다() {
		ClassificationDecision decision = ruleEngine.classify(false, VisionAnalysisOutcome.unavailable());

		assertThat(decision.aiClassification()).isEqualTo(AiClassification.REVIEW_REQUIRED);
		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.FLAGGED_FOR_REVIEW);
		assertThat(decision.reviewPriority()).isEqualTo(ReviewPriority.NORMAL);
	}

	@Test
	void 기대_객체_미탐지면_REJECT_CANDIDATE_HIGH이다() {
		ClassificationDecision decision = ruleEngine.classify(false, vision(false, 0.9, List.of()));

		assertThat(decision.aiClassification()).isEqualTo(AiClassification.REJECT_CANDIDATE);
		assertThat(decision.reviewPriority()).isEqualTo(ReviewPriority.HIGH);
	}

	@Test
	void 이상징후가_있으면_관련성이_높아도_REVIEW_REQUIRED_NORMAL이다() {
		ClassificationDecision decision = ruleEngine.classify(false, vision(true, 0.95, List.of("워터마크 불일치")));

		assertThat(decision.aiClassification()).isEqualTo(AiClassification.REVIEW_REQUIRED);
		assertThat(decision.reviewPriority()).isEqualTo(ReviewPriority.NORMAL);
	}

	@Test
	void 관련성_점수가_임계치_미만이면_REVIEW_REQUIRED_NORMAL이다() {
		ClassificationDecision decision = ruleEngine.classify(false, vision(true, 0.49, List.of()));

		assertThat(decision.aiClassification()).isEqualTo(AiClassification.REVIEW_REQUIRED);
	}

	@Test
	void 관련성_점수가_임계치와_같으면_경계는_포함되지_않아_PASS이다() {
		ClassificationDecision decision = ruleEngine.classify(false, vision(true, 0.5, List.of()));

		assertThat(decision.aiClassification()).isEqualTo(AiClassification.PASS);
	}

	@Test
	void 객체탐지_이상없음_관련성높음이면_PASS_AUTO_VALID_즉시반영이다() {
		ClassificationDecision decision = ruleEngine.classify(false, vision(true, 0.9, List.of()));

		assertThat(decision.aiClassification()).isEqualTo(AiClassification.PASS);
		assertThat(decision.reviewStatus()).isEqualTo(ReviewStatus.AUTO_VALID);
		assertThat(decision.reviewPriority()).isEqualTo(ReviewPriority.NORMAL);
		assertThat(decision.countedInScore()).isTrue();
	}
}

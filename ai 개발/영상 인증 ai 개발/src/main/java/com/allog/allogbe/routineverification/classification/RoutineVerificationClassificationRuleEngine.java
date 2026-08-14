package com.allog.allogbe.routineverification.classification;

import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.ReviewPriority;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import com.allog.allogbe.routineverification.vision.VisionAnalysisOutcome;
import org.springframework.stereotype.Component;

/**
 * STAGE 3(시간 게이트)·5(중복)·6(Vision) 결과를 결합해 aiClassification 을 산출하는 "분류기"이다.
 * ⚠️ 이 클래스는 "판정자"가 아니다 — reviewStatus 는 AUTO_VALID 또는 FLAGGED_FOR_REVIEW 까지만
 * 도달하며, VALID_CONFIRMED/INVALIDATED 로의 전환은 상호신고 + 운영자 검토(관리자 API)에서만 이뤄진다.
 *
 * 설계 방향: AI는 "확실한 이상 신호"만 러프하게 걸러내고, 애매한 판단(관련성 점수, 구도)은 붙잡지
 * 않는다 — 그 영역은 참여자 상호신고에 맡긴다. relevanceScore/isFramedProperly는 계속 수집·저장은
 * 하지만(추후 신고 발생 시 운영자 참고 자료), 이 분류에서 게이트로 쓰지 않는다.
 *
 * 전체 우선순위(규칙 0~4). 규칙 0은 이 클래스가 아니라 {@link RoutineVerificationClassificationPipeline}
 * 이 실행하지만, 판단 흐름을 온전히 보려면 여기 함께 적어둔다:
 *  0) [파이프라인] 화질 게이트(선명도/해상도) 실패 -> AI 호출 없이 즉시 거부(예외), 아래 1~4는 실행되지 않음
 *  1) 중복(isDuplicate) -> REJECT_CANDIDATE (Vision 결과와 무관하게 최우선)
 *  2) Vision API 3회 재시도 후에도 실패(available=false) -> REVIEW_REQUIRED (사용자 귀책 아님)
 *  3) 기대 객체 미탐지(objectPresence=false) -> REJECT_CANDIDATE ("전혀 무관한 사진")
 *  4) 이상징후(anomalyFlags) 존재 -> REJECT_CANDIDATE (조작/도용/화면 재촬영 등 확실한 이상 신호)
 *  5) 그 외 -> PASS
 */
@Component
public class RoutineVerificationClassificationRuleEngine {

	public ClassificationDecision classify(boolean isDuplicate, VisionAnalysisOutcome visionOutcome) {
		AiClassification aiClassification = decideAiClassification(isDuplicate, visionOutcome);
		return switch (aiClassification) {
			case PASS -> new ClassificationDecision(
					AiClassification.PASS, ReviewStatus.AUTO_VALID, ReviewPriority.NORMAL, true);
			case REVIEW_REQUIRED -> new ClassificationDecision(
					AiClassification.REVIEW_REQUIRED, ReviewStatus.FLAGGED_FOR_REVIEW, ReviewPriority.NORMAL, false);
			case REJECT_CANDIDATE -> new ClassificationDecision(
					AiClassification.REJECT_CANDIDATE, ReviewStatus.FLAGGED_FOR_REVIEW, ReviewPriority.HIGH, false);
		};
	}

	private AiClassification decideAiClassification(boolean isDuplicate, VisionAnalysisOutcome visionOutcome) {
		if (isDuplicate) {
			return AiClassification.REJECT_CANDIDATE;
		}
		if (!visionOutcome.available()) {
			return AiClassification.REVIEW_REQUIRED;
		}

		VisionAnalysisResult vision = visionOutcome.result();
		if (!Boolean.TRUE.equals(vision.getObjectPresence())) {
			return AiClassification.REJECT_CANDIDATE;
		}

		boolean hasAnomalies = vision.getAnomalyFlags() != null && !vision.getAnomalyFlags().isEmpty();
		if (hasAnomalies) {
			return AiClassification.REJECT_CANDIDATE;
		}

		return AiClassification.PASS;
	}
}

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
 * 전체 우선순위(규칙 0~5). 규칙 0은 이 클래스가 아니라 {@link RoutineVerificationClassificationPipeline}
 * 이 실행하지만, 판단 흐름을 온전히 보려면 여기 함께 적어둔다:
 *  0) [파이프라인] 화질 게이트(선명도/해상도) 실패 -> AI 호출 없이 즉시 거부(예외), 아래 1~5는 실행되지 않음
 *  1) 중복(isDuplicate) -> REJECT_CANDIDATE (Vision 결과와 무관하게 최우선)
 *  2) Vision API 3회 재시도 후에도 실패(available=false) -> REVIEW_REQUIRED (사용자 귀책 아님)
 *  3) 기대 객체 미탐지(objectPresence=false) -> REJECT_CANDIDATE ("전혀 무관한 사진")
 *  4) 이상징후 존재 또는 관련성 애매(relevanceScore &lt; RELEVANCE_THRESHOLD) -> REVIEW_REQUIRED
 *  5) 그 외, 단 구도(isFramedProperly)가 false 이면 PASS 대신 REVIEW_REQUIRED -> 아니면 PASS
 *
 * 구도(프레이밍) 판단은 영상 품질 확인 기능의 일부로, Vision AI(STAGE6)가 "필터" 신호로만 제공한다 —
 * 이 값 자체가 판정을 내리지 않으며 위 5번의 PASS 조건에서만 참조된다("구도도 정상이어야 PASS").
 * 선명도/해상도는 이 규칙엔진이 아니라 규칙 0(파이프라인의 화질 게이트)에서 이미 걸러진다.
 */
@Component
public class RoutineVerificationClassificationRuleEngine {

	/**
	 * relevanceScore 가 이 값 미만이면 "관련성 애매"로 간주해 REVIEW_REQUIRED 로 보낸다.
	 * 객체가 탐지되었더라도(objectPresence=true) 전체적인 연관성 점수가 중간 미만이면
	 * 자동 승인 대신 사람이 한 번 더 보는 것이 안전하다고 판단한 임계값이다.
	 * 실 데이터가 쌓이기 전까지는 검증되지 않은 초기값이며 STAGE10/운영 데이터로 재보정이 필요하다.
	 */
	static final double RELEVANCE_THRESHOLD = 0.5;

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
		boolean ambiguousRelevance = vision.getRelevanceScore() == null
				|| vision.getRelevanceScore() < RELEVANCE_THRESHOLD;
		boolean framingIssue = Boolean.FALSE.equals(vision.getFramedProperly());
		if (hasAnomalies || ambiguousRelevance || framingIssue) {
			return AiClassification.REVIEW_REQUIRED;
		}

		return AiClassification.PASS;
	}
}

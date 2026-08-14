package com.allog.allogbe.routineverification.vision;

import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;

/**
 * 3회 재시도 후에도 실패하면 available=false 로 반환된다.
 * STAGE7 규칙엔진은 available=false 인 경우 사용자 귀책이 아니므로 REJECT_CANDIDATE 가 아닌
 * REVIEW_REQUIRED 로 강제 분류해야 한다 (이 클래스 자체는 그 매핑을 수행하지 않는다).
 */
public record VisionAnalysisOutcome(boolean available, VisionAnalysisResult result) {

	public static VisionAnalysisOutcome success(VisionAnalysisResult result) {
		return new VisionAnalysisOutcome(true, result);
	}

	public static VisionAnalysisOutcome unavailable() {
		return new VisionAnalysisOutcome(false, null);
	}
}

package com.allog.allogbe.routineverification.vision;

import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;

/** Vision AI 호출 포트. 실패 시 {@link VisionAnalysisAttemptException} 을 던진다. */
public interface VisionAnalysisClient {

	VisionAnalysisResult analyze(VisionAnalysisRequest request);
}

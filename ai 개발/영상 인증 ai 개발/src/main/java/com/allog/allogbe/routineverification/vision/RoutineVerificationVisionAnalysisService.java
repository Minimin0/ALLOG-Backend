package com.allog.allogbe.routineverification.vision;

import org.springframework.stereotype.Component;

/**
 * Vision API 호출 재시도 오케스트레이션 (STAGE6). 최대 3회 시도 후에도 실패하면
 * VisionAnalysisOutcome.unavailable() 을 반환한다 — 예외를 던지지 않는다.
 * (API 오류는 사용자 귀책이 아니므로 제출 자체를 거부해서는 안 된다.)
 */
@Component
public class RoutineVerificationVisionAnalysisService {

	private static final int MAX_ATTEMPTS = 3;

	private final VisionAnalysisClient client;

	public RoutineVerificationVisionAnalysisService(VisionAnalysisClient client) {
		this.client = client;
	}

	public VisionAnalysisOutcome analyze(VisionAnalysisRequest request) {
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			try {
				return VisionAnalysisOutcome.success(client.analyze(request));
			} catch (VisionAnalysisAttemptException ignored) {
				// 다음 시도로 재시도. 마지막 시도까지 실패하면 아래에서 unavailable 반환.
			}
		}
		return VisionAnalysisOutcome.unavailable();
	}
}

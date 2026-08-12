package com.allog.allogbe.routineverification.vision;

/** Vision API 호출/파싱 단일 시도 실패. 재시도 대상이다 (최대 3회). */
public class VisionAnalysisAttemptException extends RuntimeException {

	public VisionAnalysisAttemptException(String message) {
		super(message);
	}

	public VisionAnalysisAttemptException(String message, Throwable cause) {
		super(message, cause);
	}
}

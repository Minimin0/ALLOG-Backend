package com.allog.allogbe.routineverification.media;

/** 프레임 추출 단일 시도 실패. 재시도 대상이며, 최종 실패는 {@link FrameCaptureException} 으로 별도 표현한다. */
public class FrameExtractionAttemptException extends RuntimeException {

	public FrameExtractionAttemptException(String message) {
		super(message);
	}

	public FrameExtractionAttemptException(String message, Throwable cause) {
		super(message, cause);
	}
}

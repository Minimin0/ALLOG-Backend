package com.allog.allogbe.routineverification.media;

/** 최대 재시도 횟수(3회)를 모두 소진했을 때 던지는 최종 실패. */
public class FrameCaptureException extends RuntimeException {

	public FrameCaptureException(String message, Throwable cause) {
		super(message, cause);
	}
}

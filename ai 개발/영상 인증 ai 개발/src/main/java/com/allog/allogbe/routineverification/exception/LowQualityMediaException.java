package com.allog.allogbe.routineverification.exception;

/**
 * STAGE4 직후 알고리즘 기반 화질 게이트(선명도/해상도) 실패. AI 호출 없이 즉시 거부한다.
 * reasonCode 는 LOW_QUALITY_BLUR 또는 LOW_RESOLUTION.
 */
public class LowQualityMediaException extends RuntimeException {

	private final String reasonCode;

	public LowQualityMediaException(String reasonCode, String message) {
		super(message);
		this.reasonCode = reasonCode;
	}

	public String getReasonCode() {
		return reasonCode;
	}
}

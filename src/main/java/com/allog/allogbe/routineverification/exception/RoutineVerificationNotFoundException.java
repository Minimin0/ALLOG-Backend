package com.allog.allogbe.routineverification.exception;

public class RoutineVerificationNotFoundException extends RuntimeException {

	public RoutineVerificationNotFoundException(Long id) {
		super("존재하지 않는 인증 제출입니다: " + id);
	}
}

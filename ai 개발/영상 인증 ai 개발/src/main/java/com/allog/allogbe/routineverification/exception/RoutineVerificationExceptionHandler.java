package com.allog.allogbe.routineverification.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RoutineVerificationExceptionHandler {

	@ExceptionHandler(DisallowedSubmissionTypeException.class)
	public ResponseEntity<RoutineVerificationErrorResponse> handleDisallowedSubmissionType(
			DisallowedSubmissionTypeException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new RoutineVerificationErrorResponse("DISALLOWED_SUBMISSION_TYPE", e.getMessage()));
	}

	@ExceptionHandler(OutsideVerificationTimeWindowException.class)
	public ResponseEntity<RoutineVerificationErrorResponse> handleOutsideVerificationTimeWindow(
			OutsideVerificationTimeWindowException e) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
				.body(new RoutineVerificationErrorResponse("OUTSIDE_VERIFICATION_TIME_WINDOW", e.getMessage()));
	}

	@ExceptionHandler(RoutineVerificationNotFoundException.class)
	public ResponseEntity<RoutineVerificationErrorResponse> handleNotFound(RoutineVerificationNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new RoutineVerificationErrorResponse("VERIFICATION_NOT_FOUND", e.getMessage()));
	}

	@ExceptionHandler(InvalidAdminReviewStatusException.class)
	public ResponseEntity<RoutineVerificationErrorResponse> handleInvalidAdminReviewStatus(
			InvalidAdminReviewStatusException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new RoutineVerificationErrorResponse("INVALID_ADMIN_REVIEW_STATUS", e.getMessage()));
	}

	/** 이미 최종 확정된 건에 대한 재확정 시도 등 상태 충돌. */
	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<RoutineVerificationErrorResponse> handleIllegalState(IllegalStateException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new RoutineVerificationErrorResponse("VERIFICATION_STATE_CONFLICT", e.getMessage()));
	}
}

package com.allog.auth.controller;

import com.allog.auth.application.DuplicateLoginIdException;
import com.allog.auth.application.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalidRequest(MethodArgumentNotValidException exception) {
        List<Detail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new Detail(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력값을 확인해 주세요.", details);
    }

    @ExceptionHandler(DuplicateLoginIdException.class)
    ResponseEntity<ApiErrorResponse> duplicate(DuplicateLoginIdException ignored) {
        return response(HttpStatus.CONFLICT, "LOGIN_ID_ALREADY_EXISTS", "이미 사용 중인 아이디예요.", List.of());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiErrorResponse> invalidCredentials(InvalidCredentialsException ignored) {
        return response(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않아요.", List.of());
    }

    @ExceptionHandler(LoginRateLimiter.LoginLimitExceededException.class)
    ResponseEntity<ApiErrorResponse> loginRateLimited(LoginRateLimiter.LoginLimitExceededException ignored) {
        return response(
                HttpStatus.TOO_MANY_REQUESTS,
                "LOGIN_RATE_LIMITED",
                "로그인 시도가 너무 많아요. 잠시 후 다시 시도해 주세요.",
                List.of()
        );
    }

    @ExceptionHandler(LoginRateLimiter.SignupLimitExceededException.class)
    ResponseEntity<ApiErrorResponse> signupRateLimited(LoginRateLimiter.SignupLimitExceededException ignored) {
        return response(
                HttpStatus.TOO_MANY_REQUESTS,
                "SIGNUP_RATE_LIMITED",
                "회원가입 시도가 너무 많아요. 잠시 후 다시 시도해 주세요.",
                List.of()
        );
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            List<Detail> details
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(new Error(code, message, details)));
    }

    public record ApiErrorResponse(Error error) {
    }

    public record Error(String code, String message, List<Detail> details) {
    }

    public record Detail(String field, String reason) {
    }
}

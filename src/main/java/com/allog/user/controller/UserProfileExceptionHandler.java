package com.allog.user.controller;

import com.allog.user.domain.UserProfileValidationException;
import com.allog.user.dto.InvalidFieldException;
import com.allog.user.dto.UnknownJsonFieldException;
import com.allog.user.service.ProfileAlreadyExistsException;
import com.allog.user.service.ProfileNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Scoped to this controller on purpose. The rest of the API answers with a bare status and changing
 * that globally is not this milestone's business, but the profile endpoints need stable machine
 * codes for the Android client.
 *
 * <p>No error body ever carries a submitted value - only the field name and the rule it broke.
 */
@RestControllerAdvice(assignableTypes = UserProfileController.class)
public class UserProfileExceptionHandler {

    private static final String VALIDATION_MESSAGE = "입력값을 확인해 주세요.";

    /**
     * Jackson wraps whatever a setter throws, so the cause chain is where the real reason lives. An
     * unknown property is reported as such; anything else here is malformed JSON or an unusable value.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadable(HttpMessageNotReadableException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownJsonFieldException unknown) {
                return badRequest(new ApiErrorResponse.Error(
                        "UNKNOWN_FIELD",
                        "요청에 정의되지 않은 필드가 있습니다.",
                        List.of(new ApiErrorResponse.Detail(unknown.fieldName(), "is not a known field"))
                ));
            }
            if (cause instanceof InvalidFieldException invalid) {
                return badRequest(validationError(invalid));
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return badRequest(new ApiErrorResponse.Error("VALIDATION_ERROR", VALIDATION_MESSAGE, List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalidBody(MethodArgumentNotValidException exception) {
        List<ApiErrorResponse.Detail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(UserProfileExceptionHandler::detail)
                .toList();
        return badRequest(new ApiErrorResponse.Error("VALIDATION_ERROR", VALIDATION_MESSAGE, details));
    }

    @ExceptionHandler(InvalidFieldException.class)
    ResponseEntity<ApiErrorResponse> invalidField(InvalidFieldException exception) {
        return badRequest(validationError(exception));
    }

    @ExceptionHandler(UnknownJsonFieldException.class)
    ResponseEntity<ApiErrorResponse> unknownField(UnknownJsonFieldException exception) {
        return badRequest(new ApiErrorResponse.Error(
                "UNKNOWN_FIELD",
                "요청에 정의되지 않은 필드가 있습니다.",
                List.of(new ApiErrorResponse.Detail(exception.fieldName(), "is not a known field"))
        ));
    }

    /**
     * Only the domain's own validation type, never {@code IllegalArgumentException} at large: a bad
     * argument thrown by Hibernate or the JDK is a server fault, and answering 400 with its message
     * would both misreport it and leak internals.
     */
    @ExceptionHandler(UserProfileValidationException.class)
    ResponseEntity<ApiErrorResponse> domainRejection(UserProfileValidationException exception) {
        return badRequest(new ApiErrorResponse.Error(
                "VALIDATION_ERROR",
                VALIDATION_MESSAGE,
                List.of(new ApiErrorResponse.Detail(exception.fieldName(), exception.reason()))
        ));
    }

    @ExceptionHandler(ProfileNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(ProfileNotFoundException ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(
                new ApiErrorResponse.Error("PROFILE_NOT_FOUND", "프로필이 아직 없습니다.", List.of())));
    }

    @ExceptionHandler(ProfileAlreadyExistsException.class)
    ResponseEntity<ApiErrorResponse> alreadyExists(ProfileAlreadyExistsException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                new ApiErrorResponse.Error("PROFILE_ALREADY_EXISTS", "이미 프로필이 있습니다.", List.of())));
    }

    private static ApiErrorResponse.Error validationError(InvalidFieldException exception) {
        return new ApiErrorResponse.Error(
                "VALIDATION_ERROR",
                VALIDATION_MESSAGE,
                List.of(new ApiErrorResponse.Detail(exception.fieldName(), exception.reason()))
        );
    }

    private static ApiErrorResponse.Detail detail(FieldError error) {
        return new ApiErrorResponse.Detail(error.getField(), error.getDefaultMessage());
    }

    private static ResponseEntity<ApiErrorResponse> badRequest(ApiErrorResponse.Error error) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(error));
    }

    public record ApiErrorResponse(Error error) {

        public record Error(String code, String message, List<Detail> details) {
        }

        public record Detail(String field, String reason) {
        }
    }
}

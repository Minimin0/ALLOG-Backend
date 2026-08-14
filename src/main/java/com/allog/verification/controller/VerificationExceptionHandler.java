package com.allog.verification.controller;

import com.allog.verification.service.VerificationCommandConflictException;
import com.allog.verification.service.VerificationMediaCommandException;
import com.allog.verification.service.VerificationMembershipNotFoundException;
import com.allog.verification.storage.VerificationMediaStorage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice(assignableTypes = VerificationController.class)
public class VerificationExceptionHandler {

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<Void> invalidRequest(Exception ignored) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(VerificationMembershipNotFoundException.class)
    ResponseEntity<Void> notFound(VerificationMembershipNotFoundException ignored) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(VerificationCommandConflictException.class)
    ResponseEntity<Void> conflict(VerificationCommandConflictException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(VerificationMediaCommandException.class)
    ResponseEntity<Void> mediaCommand(VerificationMediaCommandException exception) {
        HttpStatus status = switch (exception.reason()) {
            case INVALID_SIZE -> HttpStatus.BAD_REQUEST;
            case MEDIA_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case UNSUPPORTED_CONTENT_TYPE, CONTENT_TYPE_MISMATCH -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case METADATA_CONFLICT, MEDIA_NOT_BOUND, MEDIA_NOT_UPLOADED, BINDING_MISMATCH, SIZE_MISMATCH ->
                    HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).build();
    }

    @ExceptionHandler(VerificationMediaStorage.StorageException.class)
    ResponseEntity<Void> storage(VerificationMediaStorage.StorageException exception) {
        HttpStatus status = switch (exception.reason()) {
            case NOT_FOUND -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CONFIGURATION -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).build();
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Void> invariantFailure(IllegalStateException ignored) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}

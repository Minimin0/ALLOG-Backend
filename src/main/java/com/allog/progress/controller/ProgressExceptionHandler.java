package com.allog.progress.controller;

import com.allog.progress.service.ProgressNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ProgressController.class)
public class ProgressExceptionHandler {

    @ExceptionHandler(ProgressNotFoundException.class)
    ResponseEntity<Void> notFound(ProgressNotFoundException ignored) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Void> invariantFailure(IllegalStateException ignored) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}

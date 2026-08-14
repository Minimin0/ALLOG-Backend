package com.allog.ai.coaching.controller;

import com.allog.ai.coaching.production.AiCoachAccessDeniedException;
import com.allog.ai.coaching.production.AiCoachParticipationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ProductionAiCoachController.class)
public class ProductionAiCoachExceptionHandler {

    @ExceptionHandler({
            AiCoachParticipationNotFoundException.class,
            AiCoachAccessDeniedException.class
    })
    ResponseEntity<Void> notFound(Exception ignored) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Void> invariantFailure(IllegalStateException ignored) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}

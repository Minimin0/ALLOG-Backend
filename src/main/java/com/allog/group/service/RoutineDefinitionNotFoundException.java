package com.allog.group.service;

public class RoutineDefinitionNotFoundException extends RuntimeException {

    public RoutineDefinitionNotFoundException(Long routineDefinitionId) {
        super("routine definition not found: " + routineDefinitionId);
    }
}

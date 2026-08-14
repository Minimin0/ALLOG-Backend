package com.allog.ai.coaching.dto;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.RoutineState;

import java.util.Objects;

public record AiCoachResult(
        String title,
        String message,
        InsightType insightType,
        RoutineState routineState,
        ActionType actionType,
        String actionLabel,
        GenerationType generationType
) {

    public AiCoachResult {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(routineState, "routineState must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(actionLabel, "actionLabel must not be null");
        Objects.requireNonNull(generationType, "generationType must not be null");
    }
}

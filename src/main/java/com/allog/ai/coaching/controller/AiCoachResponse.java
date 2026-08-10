package com.allog.ai.coaching.controller;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.dto.AiCoachResult;

public record AiCoachResponse(
        String title,
        String message,
        InsightType insightType,
        RoutineState routineState,
        ActionType actionType,
        String actionLabel,
        GenerationType generationType
) {

    static AiCoachResponse from(AiCoachResult result) {
        return new AiCoachResponse(
                result.title(),
                result.message(),
                result.insightType(),
                result.routineState(),
                result.actionType(),
                result.actionLabel(),
                result.generationType()
        );
    }
}

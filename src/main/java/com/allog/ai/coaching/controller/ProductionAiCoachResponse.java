package com.allog.ai.coaching.controller;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.production.ProductionAiCoachResult;
import com.allog.group.domain.GroupMemberStatus;

public record ProductionAiCoachResponse(
        String title,
        String message,
        GroupMemberStatus participationStatus,
        InsightType insightType,
        RoutineState routineState,
        ActionType actionType,
        String actionLabel,
        GenerationType generationType
) {

    static ProductionAiCoachResponse from(ProductionAiCoachResult result) {
        return new ProductionAiCoachResponse(
                result.title(),
                result.message(),
                result.participationStatus(),
                result.insightType(),
                result.routineState(),
                result.actionType(),
                result.actionLabel(),
                result.generationType()
        );
    }
}

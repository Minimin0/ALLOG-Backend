package com.allog.ai.coaching.production;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.dto.AiCoachResult;
import com.allog.group.domain.GroupMemberStatus;

import java.util.Objects;

public record ProductionAiCoachResult(
        String title,
        String message,
        GroupMemberStatus participationStatus,
        InsightType insightType,
        RoutineState routineState,
        ActionType actionType,
        String actionLabel,
        GenerationType generationType
) {

    public ProductionAiCoachResult {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(message, "message must not be null");
        participationStatus = Objects.requireNonNull(
                participationStatus,
                "participationStatus must not be null"
        );
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(actionLabel, "actionLabel must not be null");
        Objects.requireNonNull(generationType, "generationType must not be null");
        if (participationStatus == GroupMemberStatus.ACTIVE && routineState == null) {
            throw new IllegalArgumentException("ACTIVE result requires routineState");
        }
        if (participationStatus != GroupMemberStatus.ACTIVE && insightType != null) {
            throw new IllegalArgumentException("lifecycle result must not contain progress insight");
        }
    }

    public static ProductionAiCoachResult active(AiCoachResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return new ProductionAiCoachResult(
                result.title(),
                result.message(),
                GroupMemberStatus.ACTIVE,
                result.insightType(),
                result.routineState(),
                result.actionType(),
                result.actionLabel(),
                result.generationType()
        );
    }
}

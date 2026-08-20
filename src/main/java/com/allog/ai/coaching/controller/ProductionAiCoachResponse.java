package com.allog.ai.coaching.controller;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.FollowUpQuestion;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.production.ProductionAiCoachResult;
import com.allog.group.domain.GroupMemberStatus;

import java.util.Arrays;
import java.util.List;

public record ProductionAiCoachResponse(
        String title,
        String message,
        GroupMemberStatus participationStatus,
        InsightType insightType,
        RoutineState routineState,
        ActionType actionType,
        String actionLabel,
        GenerationType generationType,
        List<SuggestedQuestion> suggestedQuestions
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
                result.generationType(),
                result.participationStatus() == GroupMemberStatus.ACTIVE
                        ? Arrays.stream(FollowUpQuestion.values()).map(SuggestedQuestion::from).toList()
                        : List.of()
        );
    }

    public record SuggestedQuestion(FollowUpQuestion id, String label) {

        private static SuggestedQuestion from(FollowUpQuestion question) {
            return new SuggestedQuestion(question, question.label());
        }
    }
}

package com.allog.ai.coaching.selector;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.dto.CoachContext;

import java.util.Objects;

public final class CoachActionResolver {

    public CoachAction resolve(CoachContext context) {
        Objects.requireNonNull(context, "context must not be null");
        InsightType insightType = context.insight() == null ? null : context.insight().type();
        if (insightType == null) {
            return action(ActionType.NONE);
        }

        return switch (insightType) {
            case VERIFICATION_PENDING -> action(ActionType.OPEN_PROGRESS);
            case DEADLINE_APPROACHING, TODAY_NOT_COMPLETED -> action(ActionType.OPEN_CERTIFICATION);
            case GROUP_GOAL_NEAR -> action(ActionType.OPEN_GROUP);
            case STREAK_CONTINUING, STREAK_RECORD, IMPROVED_FROM_PREVIOUS -> action(ActionType.OPEN_PROGRESS);
            case COMPLETION_RISK -> context.progress().todayScheduled()
                    && !context.progress().todayCompleted()
                    && !context.progress().todayVerificationPending()
                    ? action(ActionType.OPEN_CERTIFICATION)
                    : action(ActionType.OPEN_PROGRESS);
        };
    }

    private CoachAction action(ActionType type) {
        return new CoachAction(type, switch (type) {
            case OPEN_CERTIFICATION -> "인증하기";
            case OPEN_GROUP -> "그룹 현황 보기";
            case OPEN_PROGRESS -> "진행 현황 보기";
            case NONE -> "";
        });
    }

    public record CoachAction(ActionType type, String label) {
        public CoachAction {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(label, "label must not be null");
        }
    }
}

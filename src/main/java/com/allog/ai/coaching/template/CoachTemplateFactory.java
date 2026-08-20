package com.allog.ai.coaching.template;

import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.dto.AiCoachText;
import com.allog.ai.coaching.dto.CoachContext;

import java.util.Objects;

public final class CoachTemplateFactory {

    public AiCoachText create(CoachContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (context.followUp() != null) {
            return followUp(context);
        }
        if (context.progress().challengeCompleted()) {
            return new AiCoachText("챌린지를 완료했어요", "꾸준히 이어온 루틴을 완주했어요.");
        }
        InsightType type = context.insight() == null ? null : context.insight().type();
        if (type == null) {
            return new AiCoachText("오늘도 차분히 이어가요", "현재 진행 상태를 확인하며 루틴을 이어가면 됩니다.");
        }

        return switch (type) {
            case VERIFICATION_PENDING -> new AiCoachText(
                    "인증 결과를 확인하고 있어요",
                    "제출한 인증을 확인 중이에요. 판정이 완료되면 진행 상태에 반영돼요."
            );
            case DEADLINE_APPROACHING -> deadlineTemplate(context);
            case COMPLETION_RISK -> new AiCoachText(
                    "완주 기준을 한번 확인해요",
                    completionRiskMessage(context)
            );
            case GROUP_GOAL_NEAR -> new AiCoachText(
                    "그룹 목표가 가까워졌어요",
                    "조금만 더 참여하면 공동 목표에 도달할 수 있어요."
            );
            case STREAK_RECORD -> new AiCoachText(
                    "새로운 연속 기록이에요",
                    "현재 " + context.progress().currentStreak() + "회 연속으로 루틴을 이어가고 있어요."
            );
            case STREAK_CONTINUING -> new AiCoachText(
                    "좋은 흐름을 이어가고 있어요",
                    "현재 " + context.progress().currentStreak() + "회 연속 성공 기록을 이어가고 있어요."
            );
            case IMPROVED_FROM_PREVIOUS -> new AiCoachText(
                    "이전보다 나아지고 있어요",
                    "이번 챌린지 진행률이 이전 기록보다 좋아졌어요."
            );
            case TODAY_NOT_COMPLETED -> new AiCoachText(
                    "오늘 루틴을 아직 인증하지 않았어요",
                    "가능한 시간에 오늘 인증을 완료해 보세요."
            );
        };
    }

    private AiCoachText followUp(CoachContext context) {
        return switch (context.followUp().id()) {
            case "PACE_CHECK" -> new AiCoachText(
                    "현재 페이스를 확인했어요",
                    "목표의 " + percent(context.progress().completionRate()) + "%를 완료했고, 남은 인증은 "
                            + context.progress().remainingRequiredCount() + "번이에요."
            );
            case "NEXT_ACTION" -> new AiCoachText("지금 할 일을 정리했어요", nextAction(context));
            case "GROUP_PROGRESS" -> new AiCoachText(
                    "그룹 진행 상태예요",
                    context.group().completionRate() == null
                            ? "아직 계산할 수 있는 그룹 진행 기록이 없어요."
                            : "우리 그룹은 공동 목표의 " + percent(context.group().completionRate()) + "%를 완료했어요."
            );
            default -> throw new IllegalStateException("unsupported follow-up intent: " + context.followUp().id());
        };
    }

    private String nextAction(CoachContext context) {
        if (context.progress().todayVerificationPending()) {
            return "제출한 인증의 판정이 끝날 때까지 기다리면 돼요.";
        }
        if (context.progress().todayScheduled() && !context.progress().todayCompleted()) {
            return "오늘 인증을 마감 전에 제출하는 것이 가장 중요해요.";
        }
        if (context.progress().remainingRequiredCount() == 0) {
            return "현재 필요한 인증을 모두 채웠어요. 진행 현황을 확인해 주세요.";
        }
        return "남은 인증 기회 " + context.progress().remainingOpportunityCount() + "번 동안 "
                + context.progress().remainingRequiredCount() + "번 인증이 필요해요.";
    }

    private long percent(double rate) {
        return Math.round(rate * 100);
    }

    private String completionRiskMessage(CoachContext context) {
        int pending = context.progress().pendingDecisionCount();
        if (pending == 0) {
            return "남은 인증 기회 " + context.progress().remainingOpportunityCount()
                    + "번 동안 " + context.progress().remainingRequiredCount() + "번 인증이 필요해요.";
        }
        return "판정 대기 " + pending + "건과 남은 인증 기회 "
                + context.progress().remainingOpportunityCount() + "번을 함께 확인해 주세요.";
    }

    private AiCoachText deadlineTemplate(CoachContext context) {
        Long minutes = context.deadline().minutesRemaining();
        String message = minutes == null || context.deadline().passed()
                ? "인증 마감 시간을 확인해 주세요."
                : "인증 마감까지 약 " + minutes + "분 남았어요.";
        return new AiCoachText("오늘 인증이 아직 남아 있어요", message);
    }
}

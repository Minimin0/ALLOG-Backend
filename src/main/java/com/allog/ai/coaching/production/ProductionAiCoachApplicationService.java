package com.allog.ai.coaching.production;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.FollowUpQuestion;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.dto.AiCoachResult;
import com.allog.ai.coaching.dto.ProgressAnalysisInput;
import com.allog.ai.coaching.service.AiCoachApplicationService;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.progress.domain.GroupProgressFacts;
import com.allog.progress.domain.PersonalProgressFacts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class ProductionAiCoachApplicationService {

    private final ProductionAiCoachQueryService queryService;
    private final AiCoachApplicationService aiCoachApplicationService;

    public ProductionAiCoachApplicationService(
            ProductionAiCoachQueryService queryService,
            AiCoachApplicationService aiCoachApplicationService
    ) {
        this.queryService = Objects.requireNonNull(queryService, "queryService must not be null");
        this.aiCoachApplicationService = Objects.requireNonNull(
                aiCoachApplicationService,
                "aiCoachApplicationService must not be null"
        );
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProductionAiCoachResult generateFor(Long groupId, Long currentUserId) {
        return generate(groupId, currentUserId, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProductionAiCoachResult generateFollowUpFor(
            Long groupId,
            Long currentUserId,
            FollowUpQuestion question
    ) {
        return generate(groupId, currentUserId, Objects.requireNonNull(question, "question must not be null"));
    }

    private ProductionAiCoachResult generate(
            Long groupId,
            Long currentUserId,
            FollowUpQuestion question
    ) {
        ProductionAiCoachFacts facts = queryService.load(groupId, currentUserId);
        return switch (facts.participationStatus()) {
            case JOINED -> template(
                    "챌린지가 아직 시작되지 않았어요",
                    "그룹이 시작되면 오늘의 루틴 진행 상태를 알려드릴게요.",
                    GroupMemberStatus.JOINED,
                    null,
                    ActionType.OPEN_GROUP,
                    "그룹 현황 보기"
            );
            case ACTIVE -> active(facts, question);
            case COMPLETED -> template(
                    "챌린지를 완료했어요",
                    "완료한 진행 기록을 확인해 보세요.",
                    GroupMemberStatus.COMPLETED,
                    RoutineState.COMPLETED,
                    ActionType.OPEN_PROGRESS,
                    "진행 현황 보기"
            );
            case FAILED -> template(
                    "이번 챌린지가 종료됐어요",
                    "진행 기록은 진행 현황에서 다시 확인할 수 있어요.",
                    GroupMemberStatus.FAILED,
                    null,
                    ActionType.OPEN_PROGRESS,
                    "진행 현황 보기"
            );
            case LEFT, REMOVED -> throw new AiCoachAccessDeniedException(facts.participationStatus());
        };
    }

    private ProductionAiCoachResult active(ProductionAiCoachFacts facts, FollowUpQuestion question) {
        PersonalProgressFacts personal = facts.personalProgress().orElseThrow();
        GroupProgressFacts group = facts.groupProgress().orElseThrow();
        ProgressAnalysisInput input = new ProgressAnalysisInput(
                personal.todayScheduled(),
                personal.todayCompleted(),
                personal.todayVerificationPending(),
                personal.completedCount(),
                personal.requiredCompletionCount(),
                personal.currentStreak(),
                personal.previousBestStreak(),
                personal.remainingOpportunityCount(),
                personal.pendingDecisionCount(),
                group.groupCompletionRate(),
                null,
                personal.certificationDeadline().orElse(null),
                false
        );
        AiCoachResult result = question == null
                ? aiCoachApplicationService.generate(facts.challengeName(), input)
                : aiCoachApplicationService.generateFollowUp(facts.challengeName(), input, question);
        return ProductionAiCoachResult.active(result);
    }

    private ProductionAiCoachResult template(
            String title,
            String message,
            GroupMemberStatus status,
            RoutineState routineState,
            ActionType actionType,
            String actionLabel
    ) {
        return new ProductionAiCoachResult(
                title,
                message,
                status,
                null,
                routineState,
                actionType,
                actionLabel,
                GenerationType.TEMPLATE
        );
    }
}

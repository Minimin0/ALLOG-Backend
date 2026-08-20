package com.allog.ai.coaching.production;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.FollowUpQuestion;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.dto.AiCoachResult;
import com.allog.ai.coaching.dto.ProgressAnalysisInput;
import com.allog.ai.coaching.service.AiCoachApplicationService;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.progress.domain.GroupProgressFacts;
import com.allog.progress.domain.PersonalProgressFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionAiCoachApplicationServiceTest {

    private static final Long GROUP_ID = 1L;
    private static final Long USER_ID = 2L;

    @Mock
    private ProductionAiCoachQueryService queryService;

    @Mock
    private AiCoachApplicationService aiCoachApplicationService;

    private ProductionAiCoachApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ProductionAiCoachApplicationService(queryService, aiCoachApplicationService);
    }

    @Test
    void mapsEveryProductionProgressFieldForActiveParticipation() {
        Instant deadline = Instant.parse("2026-08-11T14:00:00Z");
        PersonalProgressFacts personal = new PersonalProgressFacts(
                true,
                false,
                true,
                3,
                5,
                2,
                1,
                1,
                1,
                Optional.of(deadline),
                GroupMemberStatus.ACTIVE
        );
        GroupProgressFacts group = new GroupProgressFacts(2, 8, 10, 0.8, 1, 1);
        when(queryService.load(GROUP_ID, USER_ID))
                .thenReturn(ProductionAiCoachFacts.active("아침 물 마시기", personal, group));
        when(aiCoachApplicationService.generate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AiCoachResult(
                        "인증 확인 중",
                        "조금만 기다려 주세요.",
                        InsightType.VERIFICATION_PENDING,
                        RoutineState.GOOD,
                        ActionType.OPEN_PROGRESS,
                        "진행 현황 보기",
                        GenerationType.AI
                ));

        ProductionAiCoachResult result = service.generateFor(GROUP_ID, USER_ID);

        ArgumentCaptor<ProgressAnalysisInput> input = ArgumentCaptor.forClass(ProgressAnalysisInput.class);
        verify(aiCoachApplicationService).generate(org.mockito.ArgumentMatchers.eq("아침 물 마시기"), input.capture());
        assertTrue(input.getValue().todayScheduled());
        assertFalse(input.getValue().todayCompleted());
        assertTrue(input.getValue().todayVerificationPending());
        assertEquals(3, input.getValue().completedCount());
        assertEquals(5, input.getValue().requiredCompletionCount());
        assertEquals(2, input.getValue().currentStreak());
        assertEquals(1, input.getValue().previousBestStreak());
        assertEquals(1, input.getValue().remainingOpportunityCount());
        assertEquals(1, input.getValue().pendingDecisionCount());
        assertEquals(0.8, input.getValue().groupCompletionRate());
        assertNull(input.getValue().previousChallengeCompletionRate());
        assertEquals(deadline, input.getValue().certificationDeadline());
        assertFalse(input.getValue().challengeCompleted());
        assertEquals(GroupMemberStatus.ACTIVE, result.participationStatus());
    }

    @Test
    void forwardsPresetQuestionWithProductionProgressFacts() {
        PersonalProgressFacts personal = new PersonalProgressFacts(
                false, false, false, 2, 5, 1, 0, 4, 0, Optional.empty(), GroupMemberStatus.ACTIVE
        );
        GroupProgressFacts group = new GroupProgressFacts(2, 4, 10, 0.4, 0, 2);
        when(queryService.load(GROUP_ID, USER_ID))
                .thenReturn(ProductionAiCoachFacts.active("아침 물 마시기", personal, group));
        when(aiCoachApplicationService.generateFollowUp(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(FollowUpQuestion.NEXT_ACTION)
        )).thenReturn(new AiCoachResult(
                "다음 행동",
                "오늘 인증을 제출해 주세요.",
                InsightType.TODAY_NOT_COMPLETED,
                RoutineState.ATTENTION,
                ActionType.OPEN_CERTIFICATION,
                "인증하기",
                GenerationType.AI
        ));

        ProductionAiCoachResult result = service.generateFollowUpFor(
                GROUP_ID,
                USER_ID,
                FollowUpQuestion.NEXT_ACTION
        );

        ArgumentCaptor<ProgressAnalysisInput> input = ArgumentCaptor.forClass(ProgressAnalysisInput.class);
        verify(aiCoachApplicationService).generateFollowUp(
                org.mockito.ArgumentMatchers.eq("아침 물 마시기"),
                input.capture(),
                org.mockito.ArgumentMatchers.eq(FollowUpQuestion.NEXT_ACTION)
        );
        assertEquals(2, input.getValue().completedCount());
        assertEquals(0.4, input.getValue().groupCompletionRate());
        assertEquals(GroupMemberStatus.ACTIVE, result.participationStatus());
    }

    @ParameterizedTest
    @MethodSource("templateCases")
    void returnsLifecycleTemplateWithoutAiEngine(
            GroupMemberStatus status,
            ActionType actionType,
            RoutineState routineState
    ) {
        when(queryService.load(GROUP_ID, USER_ID))
                .thenReturn(ProductionAiCoachFacts.lifecycle("아침 물 마시기", status));

        ProductionAiCoachResult result = service.generateFor(GROUP_ID, USER_ID);

        assertEquals(status, result.participationStatus());
        assertEquals(actionType, result.actionType());
        assertEquals(routineState, result.routineState());
        assertEquals(GenerationType.TEMPLATE, result.generationType());
        assertNull(result.insightType());
        assertFalse(result.title().isBlank());
        verifyNoInteractions(aiCoachApplicationService);
    }

    @ParameterizedTest
    @MethodSource("deniedCases")
    void deniesFormerMemberWithoutAiEngine(GroupMemberStatus status) {
        when(queryService.load(GROUP_ID, USER_ID))
                .thenReturn(ProductionAiCoachFacts.lifecycle("아침 물 마시기", status));

        assertThrows(AiCoachAccessDeniedException.class, () -> service.generateFor(GROUP_ID, USER_ID));

        verify(aiCoachApplicationService, never()).generate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static Stream<Arguments> templateCases() {
        return Stream.of(
                Arguments.of(GroupMemberStatus.JOINED, ActionType.OPEN_GROUP, null),
                Arguments.of(GroupMemberStatus.COMPLETED, ActionType.OPEN_PROGRESS, RoutineState.COMPLETED),
                Arguments.of(GroupMemberStatus.FAILED, ActionType.OPEN_PROGRESS, null)
        );
    }

    private static Stream<Arguments> deniedCases() {
        return Stream.of(
                Arguments.of(GroupMemberStatus.LEFT),
                Arguments.of(GroupMemberStatus.REMOVED)
        );
    }
}

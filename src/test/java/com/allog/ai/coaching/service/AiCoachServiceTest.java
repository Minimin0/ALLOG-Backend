package com.allog.ai.coaching.service;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.CompletionRiskLevel;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.dto.AiCoachResult;
import com.allog.ai.coaching.dto.AiCoachText;
import com.allog.ai.coaching.dto.CoachContext;
import com.allog.ai.coaching.provider.AiCoachProvider;
import com.allog.ai.coaching.provider.AiProviderException;
import com.allog.ai.coaching.selector.CoachActionResolver;
import com.allog.ai.coaching.selector.CoachActionResolver.CoachAction;
import com.allog.ai.coaching.template.CoachTemplateFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiCoachServiceTest {

    private final CoachActionResolver actionResolver = new CoachActionResolver();
    private final CoachTemplateFactory templateFactory = new CoachTemplateFactory();

    @Test
    void returnsAiTextWithBackendOwnedStateAndAction() {
        AiCoachProvider provider = context -> new AiCoachText("새 제목", "새 메시지");
        AiCoachService service = service(provider);

        AiCoachResult result = service.generate(context(
                InsightType.DEADLINE_APPROACHING,
                true,
                false,
                RoutineState.ATTENTION
        ));

        assertAll(
                () -> assertEquals("새 제목", result.title()),
                () -> assertEquals("새 메시지", result.message()),
                () -> assertEquals(InsightType.DEADLINE_APPROACHING, result.insightType()),
                () -> assertEquals(RoutineState.ATTENTION, result.routineState()),
                () -> assertEquals(ActionType.OPEN_CERTIFICATION, result.actionType()),
                () -> assertEquals("인증하기", result.actionLabel()),
                () -> assertEquals(GenerationType.AI, result.generationType())
        );
    }

    @Test
    void fallsBackWhenProviderFails() {
        AiCoachProvider provider = context -> {
            throw new AiProviderException(AiProviderException.Category.HTTP, "upstream failure");
        };

        AiCoachResult result = service(provider).generate(context(
                InsightType.DEADLINE_APPROACHING,
                true,
                false,
                RoutineState.ATTENTION
        ));

        assertAll(
                () -> assertEquals(GenerationType.TEMPLATE, result.generationType()),
                () -> assertEquals("오늘 인증이 아직 남아 있어요", result.title()),
                () -> assertEquals(ActionType.OPEN_CERTIFICATION, result.actionType())
        );
    }

    @Test
    void fallsBackWithoutCallingUnavailableProvider() {
        AtomicBoolean called = new AtomicBoolean();
        AiCoachProvider provider = new AiCoachProvider() {
            @Override
            public AiCoachText generate(CoachContext context) {
                called.set(true);
                return new AiCoachText("제목", "메시지");
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };

        AiCoachResult result = service(provider).generate(context(
                InsightType.TODAY_NOT_COMPLETED,
                true,
                false,
                RoutineState.ATTENTION
        ));

        assertAll(
                () -> assertFalse(called.get()),
                () -> assertEquals(GenerationType.TEMPLATE, result.generationType())
        );
    }

    @Test
    void fallsBackWhenProviderAvailabilityCheckFails() {
        AiCoachProvider provider = new AiCoachProvider() {
            @Override
            public AiCoachText generate(CoachContext context) {
                return new AiCoachText("제목", "메시지");
            }

            @Override
            public boolean isAvailable() {
                throw new IllegalStateException("configuration failure");
            }
        };

        AiCoachResult result = service(provider).generate(context(
                InsightType.TODAY_NOT_COMPLETED,
                true,
                false,
                RoutineState.ATTENTION
        ));

        assertEquals(GenerationType.TEMPLATE, result.generationType());
    }

    @Test
    void fallsBackWhenProviderReturnsInvalidNullOutput() {
        AiCoachProvider provider = context -> null;

        AiCoachResult result = service(provider).generate(context(
                InsightType.STREAK_CONTINUING,
                true,
                true,
                RoutineState.GOOD
        ));

        assertAll(
                () -> assertEquals(GenerationType.TEMPLATE, result.generationType()),
                () -> assertEquals(ActionType.OPEN_PROGRESS, result.actionType())
        );
    }

    @Test
    void fallsBackOnUnexpectedProviderRuntimeFailureWithoutCatchingJvmErrors() {
        AiCoachProvider provider = context -> {
            throw new IllegalStateException("unexpected provider bug");
        };

        AiCoachResult result = service(provider).generate(context(
                InsightType.GROUP_GOAL_NEAR,
                true,
                true,
                RoutineState.GOOD
        ));

        assertEquals(GenerationType.TEMPLATE, result.generationType());
    }

    @Test
    void pendingFallbackExplainsStatusAndOpensProgress() {
        AiCoachProvider provider = new AiCoachProvider() {
            @Override
            public AiCoachText generate(CoachContext context) {
                throw new AssertionError("unavailable provider must not be called");
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };

        AiCoachResult result = service(provider).generate(context(
                InsightType.VERIFICATION_PENDING,
                true,
                false,
                RoutineState.GOOD
        ));

        assertAll(
                () -> assertEquals("인증 결과를 확인하고 있어요", result.title()),
                () -> assertEquals(ActionType.OPEN_PROGRESS, result.actionType()),
                () -> assertEquals("진행 현황 보기", result.actionLabel()),
                () -> assertEquals(GenerationType.TEMPLATE, result.generationType())
        );
    }

    @ParameterizedTest
    @MethodSource("actionCases")
    void resolvesNavigationFromBackendInsight(
            InsightType insight,
            boolean todayScheduled,
            boolean todayCompleted,
            ActionType expectedType,
            String expectedLabel
    ) {
        CoachAction action = actionResolver.resolve(context(
                insight,
                todayScheduled,
                todayCompleted,
                RoutineState.GOOD
        ));

        assertAll(
                () -> assertEquals(expectedType, action.type()),
                () -> assertEquals(expectedLabel, action.label())
        );
    }

    private static Stream<Arguments> actionCases() {
        return Stream.of(
                Arguments.of(InsightType.VERIFICATION_PENDING, true, false,
                        ActionType.OPEN_PROGRESS, "진행 현황 보기"),
                Arguments.of(InsightType.DEADLINE_APPROACHING, true, false,
                        ActionType.OPEN_CERTIFICATION, "인증하기"),
                Arguments.of(InsightType.TODAY_NOT_COMPLETED, true, false,
                        ActionType.OPEN_CERTIFICATION, "인증하기"),
                Arguments.of(InsightType.GROUP_GOAL_NEAR, true, true,
                        ActionType.OPEN_GROUP, "그룹 현황 보기"),
                Arguments.of(InsightType.STREAK_CONTINUING, true, true,
                        ActionType.OPEN_PROGRESS, "진행 현황 보기"),
                Arguments.of(InsightType.STREAK_RECORD, true, true,
                        ActionType.OPEN_PROGRESS, "진행 현황 보기"),
                Arguments.of(InsightType.IMPROVED_FROM_PREVIOUS, true, true,
                        ActionType.OPEN_PROGRESS, "진행 현황 보기"),
                Arguments.of(InsightType.COMPLETION_RISK, true, false,
                        ActionType.OPEN_CERTIFICATION, "인증하기"),
                Arguments.of(InsightType.COMPLETION_RISK, false, false,
                        ActionType.OPEN_PROGRESS, "진행 현황 보기"),
                Arguments.of(null, true, true, ActionType.NONE, "")
        );
    }

    private AiCoachService service(AiCoachProvider provider) {
        return new AiCoachService(provider, actionResolver, templateFactory);
    }

    private static CoachContext context(
            InsightType type,
            boolean todayScheduled,
            boolean todayCompleted,
            RoutineState routineState
    ) {
        return new CoachContext(
                new CoachContext.Challenge("물 마시기"),
                new CoachContext.Progress(
                        todayScheduled,
                        todayCompleted,
                        type == InsightType.VERIFICATION_PENDING,
                        0.6,
                        3,
                        2,
                        3,
                        type == InsightType.VERIFICATION_PENDING ? 1 : 0,
                        CompletionRiskLevel.LOW,
                        false
                ),
                new CoachContext.Group(0.8),
                new CoachContext.Deadline(Instant.parse("2026-08-07T09:00:00Z"), 60L, false),
                type == null ? null : new CoachContext.SelectedInsight(type, 1),
                routineState
        );
    }
}

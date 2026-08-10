package com.allog.ai.coaching.service;

import com.allog.ai.coaching.analyzer.ProgressAnalyzer;
import com.allog.ai.coaching.detector.ProgressInsightDetector;
import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.dto.AiCoachResult;
import com.allog.ai.coaching.dto.AiCoachText;
import com.allog.ai.coaching.dto.CoachContext;
import com.allog.ai.coaching.dto.ProgressAnalysisInput;
import com.allog.ai.coaching.policy.AiCoachPolicy;
import com.allog.ai.coaching.provider.AiCoachProvider;
import com.allog.ai.coaching.provider.AiProviderException;
import com.allog.ai.coaching.selector.CoachActionResolver;
import com.allog.ai.coaching.selector.InsightSelector;
import com.allog.ai.coaching.selector.RoutineStateResolver;
import com.allog.ai.coaching.template.CoachTemplateFactory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiCoachApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T03:00:00Z");

    @Test
    void orchestratesDeadlineInsightStateAndAction() {
        AiCoachResult result = application(context -> new AiCoachText("제목", "메시지"))
                .generate("물 마시기", input(true, false, 3, 5, 5, null, NOW.plusSeconds(60 * 60), false));

        assertAll(
                () -> assertEquals(InsightType.DEADLINE_APPROACHING, result.insightType()),
                () -> assertEquals(RoutineState.ATTENTION, result.routineState()),
                () -> assertEquals(ActionType.OPEN_CERTIFICATION, result.actionType()),
                () -> assertEquals(GenerationType.AI, result.generationType())
        );
    }

    @Test
    void resolvesHighCompletionRiskAsAtRisk() {
        AiCoachResult result = application(context -> new AiCoachText("제목", "메시지"))
                .generate("걷기", input(false, false, 2, 5, 2, null, null, false));

        assertAll(
                () -> assertEquals(InsightType.COMPLETION_RISK, result.insightType()),
                () -> assertEquals(RoutineState.AT_RISK, result.routineState())
        );
    }

    @Test
    void resolvesGroupGoalAction() {
        AiCoachResult result = application(context -> new AiCoachText("제목", "메시지"))
                .generate("독서", input(false, false, 3, 5, 5, 0.8, null, false));

        assertAll(
                () -> assertEquals(InsightType.GROUP_GOAL_NEAR, result.insightType()),
                () -> assertEquals(ActionType.OPEN_GROUP, result.actionType())
        );
    }

    @Test
    void explainsPendingVerificationWithoutCertificationActionOrAttentionState() {
        AiCoachResult result = application(context -> new AiCoachText("제목", "메시지"))
                .generate("물 마시기", pendingInput());

        assertAll(
                () -> assertEquals(InsightType.VERIFICATION_PENDING, result.insightType()),
                () -> assertEquals(RoutineState.GOOD, result.routineState()),
                () -> assertEquals(ActionType.OPEN_PROGRESS, result.actionType()),
                () -> assertEquals("진행 현황 보기", result.actionLabel())
        );
    }

    @Test
    void skipsProviderWhenThereIsNoInsight() {
        AtomicBoolean called = new AtomicBoolean();
        AiCoachResult result = application(trackingProvider(called))
                .generate("명상", input(false, false, 3, 5, 5, null, null, false));

        assertAll(
                () -> assertFalse(called.get()),
                () -> assertNull(result.insightType()),
                () -> assertEquals(RoutineState.GOOD, result.routineState()),
                () -> assertEquals(ActionType.NONE, result.actionType()),
                () -> assertEquals("", result.actionLabel()),
                () -> assertEquals(GenerationType.TEMPLATE, result.generationType())
        );
    }

    @Test
    void skipsProviderForCompletedChallenge() {
        AtomicBoolean called = new AtomicBoolean();
        AiCoachResult result = application(trackingProvider(called))
                .generate("달리기", input(false, false, 5, 5, 0, null, null, true));

        assertAll(
                () -> assertFalse(called.get()),
                () -> assertEquals(RoutineState.COMPLETED, result.routineState()),
                () -> assertEquals(GenerationType.TEMPLATE, result.generationType()),
                () -> assertEquals("챌린지를 완료했어요", result.title())
        );
    }

    @Test
    void returnsTemplateWhenProviderFails() {
        AiCoachProvider provider = context -> {
            throw new AiProviderException(AiProviderException.Category.CONNECTION, "failed");
        };
        AiCoachResult result = application(provider)
                .generate("물 마시기", input(true, false, 3, 5, 5, null, NOW.plusSeconds(60 * 60), false));

        assertAll(
                () -> assertEquals(InsightType.DEADLINE_APPROACHING, result.insightType()),
                () -> assertEquals(GenerationType.TEMPLATE, result.generationType())
        );
    }

    private AiCoachApplicationService application(AiCoachProvider provider) {
        AiCoachService coachService = new AiCoachService(
                provider,
                new CoachActionResolver(),
                new CoachTemplateFactory()
        );
        return new AiCoachApplicationService(
                new ProgressAnalyzer(),
                new ProgressInsightDetector(),
                new InsightSelector(),
                new RoutineStateResolver(),
                coachService,
                AiCoachPolicy.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private AiCoachProvider trackingProvider(AtomicBoolean called) {
        return new AiCoachProvider() {
            @Override
            public AiCoachText generate(CoachContext context) {
                called.set(true);
                return new AiCoachText("제목", "메시지");
            }
        };
    }

    private ProgressAnalysisInput input(
            boolean todayScheduled,
            boolean todayCompleted,
            int completedCount,
            int requiredCount,
            int remainingOpportunities,
            Double groupRate,
            Instant deadline,
            boolean completed
    ) {
        return new ProgressAnalysisInput(
                todayScheduled,
                todayCompleted,
                false,
                completedCount,
                requiredCount,
                0,
                0,
                remainingOpportunities,
                0,
                groupRate,
                null,
                deadline,
                completed
        );
    }

    private ProgressAnalysisInput pendingInput() {
        return new ProgressAnalysisInput(
                true,
                false,
                true,
                3,
                5,
                2,
                2,
                4,
                1,
                null,
                null,
                NOW.plusSeconds(60 * 60),
                false
        );
    }
}

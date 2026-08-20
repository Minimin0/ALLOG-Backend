package com.allog.ai.coaching.service;

import com.allog.ai.coaching.analyzer.ProgressAnalyzer;
import com.allog.ai.coaching.detector.ProgressInsightDetector;
import com.allog.ai.coaching.domain.ProgressInsight;
import com.allog.ai.coaching.domain.FollowUpQuestion;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.dto.AiCoachResult;
import com.allog.ai.coaching.dto.CoachContext;
import com.allog.ai.coaching.dto.ProgressAnalysisInput;
import com.allog.ai.coaching.dto.ProgressSnapshot;
import com.allog.ai.coaching.policy.AiCoachPolicy;
import com.allog.ai.coaching.selector.InsightSelector;
import com.allog.ai.coaching.selector.RoutineStateResolver;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AiCoachApplicationService {

    private final ProgressAnalyzer analyzer;
    private final ProgressInsightDetector detector;
    private final InsightSelector selector;
    private final RoutineStateResolver stateResolver;
    private final AiCoachService coachService;
    private final AiCoachPolicy policy;
    private final Clock clock;

    public AiCoachApplicationService(
            ProgressAnalyzer analyzer,
            ProgressInsightDetector detector,
            InsightSelector selector,
            RoutineStateResolver stateResolver,
            AiCoachService coachService,
            AiCoachPolicy policy,
            Clock clock
    ) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer must not be null");
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.stateResolver = Objects.requireNonNull(stateResolver, "stateResolver must not be null");
        this.coachService = Objects.requireNonNull(coachService, "coachService must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public AiCoachResult generate(String challengeName, ProgressAnalysisInput input) {
        return generate(challengeName, input, null);
    }

    public AiCoachResult generateFollowUp(
            String challengeName,
            ProgressAnalysisInput input,
            FollowUpQuestion question
    ) {
        return generate(challengeName, input, Objects.requireNonNull(question, "question must not be null"));
    }

    private AiCoachResult generate(
            String challengeName,
            ProgressAnalysisInput input,
            FollowUpQuestion question
    ) {
        ProgressSnapshot snapshot = analyzer.analyze(input, policy, clock);
        List<ProgressInsight> insights = detector.detect(snapshot, policy);
        Optional<ProgressInsight> selectedInsight = selector.select(insights);
        RoutineState routineState = stateResolver.resolve(snapshot, insights);
        CoachContext context = CoachContext.from(challengeName, snapshot, selectedInsight, routineState);
        if (question != null) {
            context = context.withFollowUp(question);
        }
        return coachService.generate(context);
    }
}

package com.allog.ai.coaching.config;

import com.allog.ai.coaching.analyzer.ProgressAnalyzer;
import com.allog.ai.coaching.detector.ProgressInsightDetector;
import com.allog.ai.coaching.policy.AiCoachPolicy;
import com.allog.ai.coaching.selector.InsightSelector;
import com.allog.ai.coaching.selector.RoutineStateResolver;
import com.allog.ai.coaching.service.AiCoachApplicationService;
import com.allog.ai.coaching.service.AiCoachService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AiCoachingConfiguration {

    @Bean
    Clock aiCoachClock() {
        return Clock.systemUTC();
    }

    @Bean
    AiCoachApplicationService aiCoachApplicationService(AiCoachService coachService, Clock aiCoachClock) {
        return new AiCoachApplicationService(
                new ProgressAnalyzer(),
                new ProgressInsightDetector(),
                new InsightSelector(),
                new RoutineStateResolver(),
                coachService,
                AiCoachPolicy.defaults(),
                aiCoachClock
        );
    }
}

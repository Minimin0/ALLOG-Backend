package com.allog.ai.coaching.service;

import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.dto.AiCoachResult;
import com.allog.ai.coaching.dto.AiCoachText;
import com.allog.ai.coaching.dto.CoachContext;
import com.allog.ai.coaching.provider.AiCoachProvider;
import com.allog.ai.coaching.provider.AiProviderException;
import com.allog.ai.coaching.selector.CoachActionResolver;
import com.allog.ai.coaching.selector.CoachActionResolver.CoachAction;
import com.allog.ai.coaching.template.CoachTemplateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class AiCoachService {

    private static final Logger log = LoggerFactory.getLogger(AiCoachService.class);

    private final AiCoachProvider provider;
    private final CoachActionResolver actionResolver;
    private final CoachTemplateFactory templateFactory;

    public AiCoachService(
            AiCoachProvider provider,
            CoachActionResolver actionResolver,
            CoachTemplateFactory templateFactory
    ) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.actionResolver = Objects.requireNonNull(actionResolver, "actionResolver must not be null");
        this.templateFactory = Objects.requireNonNull(templateFactory, "templateFactory must not be null");
    }

    public AiCoachResult generate(CoachContext context) {
        Objects.requireNonNull(context, "context must not be null");
        CoachAction action = actionResolver.resolve(context);
        long startedAt = System.nanoTime();

        if (context.insight() == null || context.progress().challengeCompleted()) {
            return result(
                    context,
                    action,
                    templateFactory.create(context),
                    GenerationType.TEMPLATE
            );
        }

        boolean providerAvailable;
        try {
            providerAvailable = provider.isAvailable();
        } catch (RuntimeException exception) {
            return fallback(context, action, startedAt, AiProviderException.Category.UNEXPECTED);
        }
        if (!providerAvailable) {
            return fallback(context, action, startedAt, AiProviderException.Category.UNAVAILABLE);
        }

        try {
            AiCoachText text = provider.generate(context);
            if (text == null) {
                throw new AiProviderException(
                        AiProviderException.Category.VALIDATION,
                        "AI coach provider returned no text"
                );
            }
            AiCoachResult result = result(context, action, text, GenerationType.AI);
            log.info("AI Coach generation success: latencyMs={}", elapsedMillis(startedAt));
            return result;
        } catch (AiProviderException exception) {
            return fallback(context, action, startedAt, exception.category());
        } catch (RuntimeException exception) {
            return fallback(context, action, startedAt, AiProviderException.Category.UNEXPECTED);
        }
    }

    private AiCoachResult fallback(
            CoachContext context,
            CoachAction action,
            long startedAt,
            AiProviderException.Category category
    ) {
        log.warn("AI Coach fallback: category={}, latencyMs={}", category, elapsedMillis(startedAt));
        return result(context, action, templateFactory.create(context), GenerationType.TEMPLATE);
    }

    private AiCoachResult result(
            CoachContext context,
            CoachAction action,
            AiCoachText text,
            GenerationType generationType
    ) {
        InsightType insightType = context.insight() == null ? null : context.insight().type();
        return new AiCoachResult(
                text.title(),
                text.message(),
                insightType,
                context.routineState(),
                action.type(),
                action.label(),
                generationType
        );
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}

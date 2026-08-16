package com.allog.group.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Walks the groups whose lifecycle can still move and hands each one to the reconciler.
 *
 * <p>It holds no transaction of its own: finding candidates and acting on them are separate, so one
 * long sweep never locks the whole table. A group that fails is logged and skipped rather than
 * stopping the others - one broken group must not freeze everyone else's.
 */
@Component
@ConditionalOnProperty(name = "allog.group.lifecycle.enabled", havingValue = "true")
public class RoutineGroupLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(RoutineGroupLifecycleScheduler.class);

    private final RoutineGroupLifecycleReconciler reconciler;
    private final com.allog.group.repository.RoutineGroupRepository routineGroupRepository;
    private final RoutineGroupLifecycleProperties properties;

    public RoutineGroupLifecycleScheduler(
            RoutineGroupLifecycleReconciler reconciler,
            com.allog.group.repository.RoutineGroupRepository routineGroupRepository,
            RoutineGroupLifecycleProperties properties
    ) {
        this.reconciler = Objects.requireNonNull(reconciler);
        this.routineGroupRepository = Objects.requireNonNull(routineGroupRepository);
        this.properties = Objects.requireNonNull(properties);
    }

    @Scheduled(fixedDelayString = "${allog.group.lifecycle.reconcile-delay:60s}")
    public void reconcileDueGroups() {
        long afterId = 0L;
        while (true) {
            List<Long> candidates = routineGroupRepository.findReconcilableIdsAfter(
                    afterId, PageRequest.of(0, properties.batchSize()));
            if (candidates.isEmpty()) {
                return;
            }
            for (Long groupId : candidates) {
                reconcileQuietly(groupId);
            }
            afterId = candidates.get(candidates.size() - 1);
        }
    }

    private void reconcileQuietly(Long groupId) {
        try {
            reconciler.reconcile(groupId);
        } catch (RuntimeException failure) {
            // Group id and failure type only: nothing about the people in it belongs in a log.
            log.warn("routine group lifecycle reconciliation failed: groupId={} error={}",
                    groupId, failure.getClass().getSimpleName());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(RoutineGroupLifecycleProperties.class)
    @ConditionalOnProperty(name = "allog.group.lifecycle.enabled", havingValue = "true")
    static class SchedulingConfiguration {
    }
}

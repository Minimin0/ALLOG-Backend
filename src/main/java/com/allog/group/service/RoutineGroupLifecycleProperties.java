package com.allog.group.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How often the lifecycle sweep runs and how much it takes at a time. Operational settings, not
 * product rules: no group behaviour changes if these are tuned.
 *
 * <p>{@code enabled} is off by default so tests never race a background sweep; deployments turn it
 * on.
 */
@ConfigurationProperties("allog.group.lifecycle")
public record RoutineGroupLifecycleProperties(
        boolean enabled,
        Duration reconcileDelay,
        int batchSize
) {

    public RoutineGroupLifecycleProperties {
        reconcileDelay = reconcileDelay == null ? Duration.ofSeconds(60) : reconcileDelay;
        batchSize = batchSize <= 0 ? 100 : batchSize;
    }
}

package com.allog.ai.coaching.domain;

import java.util.Objects;

public record ProgressInsight(InsightType type, int priority) {

    public ProgressInsight {
        Objects.requireNonNull(type, "type must not be null");
        if (priority <= 0) {
            throw new IllegalArgumentException("priority must be positive");
        }
    }
}

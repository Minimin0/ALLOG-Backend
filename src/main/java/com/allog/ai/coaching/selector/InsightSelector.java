package com.allog.ai.coaching.selector;

import com.allog.ai.coaching.domain.ProgressInsight;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class InsightSelector {

    public Optional<ProgressInsight> select(List<ProgressInsight> insights) {
        Objects.requireNonNull(insights, "insights must not be null");
        return insights.stream().min(Comparator.comparingInt(ProgressInsight::priority));
    }
}

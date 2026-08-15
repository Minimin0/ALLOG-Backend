package com.allog.verification.analysis.controller;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Who may drive the analysis worker by hand. There is no role model yet - every authenticated user
 * is equal - so the operator set is named explicitly by configuration. Empty by default, which
 * denies everyone: a deployment that forgets to configure it cannot spend provider budget.
 */
@ConfigurationProperties("allog.verification.analysis.operations")
public record VerificationAnalysisOperationsProperties(Set<Long> operatorUserIds) {

    public VerificationAnalysisOperationsProperties {
        operatorUserIds = operatorUserIds == null ? Set.of() : Set.copyOf(operatorUserIds);
    }

    public boolean isOperator(Long userId) {
        return userId != null && operatorUserIds.contains(userId);
    }
}

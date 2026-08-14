package com.allog.verification.template.domain;

import com.allog.verification.analysis.domain.VerificationCriteria;

import java.util.Objects;

public record VerificationTemplate(
        VerificationTemplateKey key,
        String displayName,
        VerificationCriteria.Reference criteriaReference
) {

    public VerificationTemplate {
        Objects.requireNonNull(key, "key must not be null");
        displayName = requireText(displayName, "displayName");
        Objects.requireNonNull(criteriaReference, "criteriaReference must not be null");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}

package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.analysis.domain.VerificationCriteria;

import java.util.Objects;

@FunctionalInterface
public interface VerificationAnalysisProvider {

    Result analyze(VerificationCriteria.ProviderContract criteria, Evidence evidence);

    record Evidence(VerificationCriteria.MediaModality modality, String contentType, byte[] content) {

        public Evidence {
            Objects.requireNonNull(modality, "modality must not be null");
            contentType = requireText(contentType, "contentType");
            content = Objects.requireNonNull(content, "content must not be null").clone();
            if (content.length == 0) {
                throw new IllegalArgumentException("content must not be empty");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record Result(String providerModel, VerificationAnalysisObservation observation) {

        public Result {
            providerModel = requireText(providerModel, "providerModel");
            if (providerModel.length() > 100) {
                throw new IllegalArgumentException("providerModel must be at most 100 characters");
            }
            Objects.requireNonNull(observation, "observation must not be null");
        }
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

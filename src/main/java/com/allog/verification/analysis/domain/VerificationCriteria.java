package com.allog.verification.analysis.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Backend-owned, provider-neutral criteria resolved for one routine definition.
 * Product criteria are code-owned and version changes require a new {@link Reference}.
 */
public record VerificationCriteria(
        Reference reference,
        long routineDefinitionId,
        Set<MediaModality> supportedMedia,
        Set<ObservationType> requiredObservations,
        String evidenceRequirements
) {

    public VerificationCriteria {
        Objects.requireNonNull(reference, "reference must not be null");
        if (routineDefinitionId <= 0) {
            throw new IllegalArgumentException("routineDefinitionId must be positive");
        }
        supportedMedia = requireNonEmptyCopy(supportedMedia, "supportedMedia");
        requiredObservations = requireNonEmptyCopy(requiredObservations, "requiredObservations");
        evidenceRequirements = requireText(evidenceRequirements, "evidenceRequirements");
    }

    public ProviderContract providerContract() {
        return new ProviderContract(reference, supportedMedia, requiredObservations, evidenceRequirements);
    }

    public record Reference(String criteriaId, int version) {

        private static final int MAXIMUM_ID_LENGTH = 48;

        public Reference {
            criteriaId = requireText(criteriaId, "criteriaId");
            if (criteriaId.length() > MAXIMUM_ID_LENGTH || criteriaId.contains("@")) {
                throw new IllegalArgumentException("criteriaId must be at most 48 characters and must not contain @");
            }
            if (version <= 0) {
                throw new IllegalArgumentException("version must be positive");
            }
        }

        public String storageValue() {
            return criteriaId + "@" + version;
        }
    }

    /** Vendor-facing criteria deliberately excludes the internal routine definition ID. */
    public record ProviderContract(
            Reference reference,
            Set<MediaModality> supportedMedia,
            Set<ObservationType> requiredObservations,
            String evidenceRequirements
    ) {

        public ProviderContract {
            Objects.requireNonNull(reference, "reference must not be null");
            supportedMedia = requireNonEmptyCopy(supportedMedia, "supportedMedia");
            requiredObservations = requireNonEmptyCopy(requiredObservations, "requiredObservations");
            evidenceRequirements = requireText(evidenceRequirements, "evidenceRequirements");
        }
    }

    public enum MediaModality {
        PHOTO,
        VIDEO
    }

    public enum ObservationType {
        TARGET_EVIDENCE_VISIBLE,
        CRITERIA_RELEVANCE_SCORE,
        INTEGRITY_ANOMALY,
        FRAMING_SUFFICIENCY
    }

    private static <T> Set<T> requireNonEmptyCopy(Set<T> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        if (values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must contain only non-null values");
        }
        return Set.copyOf(values);
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

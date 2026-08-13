package com.allog.verification.analysis.domain;

import com.allog.routine.domain.RoutineKey;

import java.util.Objects;
import java.util.Set;

/**
 * Backend-owned, provider-neutral criteria bound to one stable Product Routine identity.
 * Product criteria are code-owned and version changes require a new {@link Reference}.
 */
public record VerificationCriteria(
        Reference reference,
        RoutineKey routineKey,
        Set<MediaModality> supportedMedia,
        Set<ObservationType> requiredObservations,
        String evidenceRequirements
) {

    public VerificationCriteria {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(routineKey, "routineKey must not be null");
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

        public static Reference fromStorageValue(String value) {
            String stored = requireText(value, "criteria reference");
            if (!stored.equals(value)) {
                throw new IllegalArgumentException("criteria reference must not contain surrounding whitespace");
            }
            int separator = stored.lastIndexOf('@');
            if (separator <= 0 || separator == stored.length() - 1) {
                throw new IllegalArgumentException("criteria reference must use <criteriaId>@<version>");
            }
            try {
                Reference reference = new Reference(
                        stored.substring(0, separator),
                        Integer.parseInt(stored.substring(separator + 1))
                );
                if (!reference.storageValue().equals(stored)) {
                    throw new IllegalArgumentException("criteria reference must use canonical numeric version");
                }
                return reference;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("criteria reference version must be a positive integer", exception);
            }
        }
    }

    /** Vendor-facing criteria deliberately excludes the internal RoutineKey. */
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

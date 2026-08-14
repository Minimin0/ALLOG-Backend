package com.allog.verification.template;

import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.template.domain.VerificationTemplate;
import com.allog.verification.template.domain.VerificationTemplateKey;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public final class VerificationTemplateCatalog {

    public static final VerificationTemplateKey MEAL_PHOTO_RECORD =
            new VerificationTemplateKey("MEAL_PHOTO_RECORD");
    public static final VerificationCriteria.Reference MEAL_PHOTO_RECORD_V1 =
            new VerificationCriteria.Reference("meal-photo-record", 1);

    private final Map<VerificationTemplateKey, VerificationTemplate> templates;
    private final Map<VerificationCriteria.Reference, VerificationCriteria> criteria;

    public VerificationTemplateCatalog() {
        this(
                List.of(new VerificationTemplate(
                        MEAL_PHOTO_RECORD,
                        "식사 사진 기록",
                        MEAL_PHOTO_RECORD_V1
                )),
                List.of(new VerificationCriteria(
                        MEAL_PHOTO_RECORD_V1,
                        MEAL_PHOTO_RECORD,
                        Set.of(VerificationCriteria.MediaModality.PHOTO),
                        Set.of(
                                VerificationCriteria.ObservationType.TARGET_EVIDENCE_VISIBLE,
                                VerificationCriteria.ObservationType.CRITERIA_RELEVANCE_SCORE,
                                VerificationCriteria.ObservationType.INTEGRITY_ANOMALY,
                                VerificationCriteria.ObservationType.FRAMING_SUFFICIENCY
                        ),
                        "Observe only whether the photo contains an assessable meal or food scene. "
                                + "Do not infer consumption, health, nutrition, amount, ownership, identity, "
                                + "capture or deadline time, or prior reuse."
                ))
        );
    }

    VerificationTemplateCatalog(
            List<VerificationTemplate> templates,
            List<VerificationCriteria> criteria
    ) {
        this.templates = uniqueTemplates(templates);
        this.criteria = uniqueCriteria(criteria);
        this.templates.values().forEach(template -> {
            VerificationCriteria boundCriteria = requireCriteria(template.criteriaReference());
            if (!template.key().equals(boundCriteria.templateKey())) {
                throw new IllegalArgumentException("template and criteria keys must match");
            }
        });
    }

    public VerificationTemplate requireTemplate(VerificationTemplateKey key) {
        VerificationTemplate template = templates.get(Objects.requireNonNull(key, "key must not be null"));
        if (template == null) {
            throw new IllegalArgumentException("unknown verification template: " + key.value());
        }
        return template;
    }

    public VerificationCriteria requireCriteria(VerificationCriteria.Reference reference) {
        VerificationCriteria resolved = criteria.get(Objects.requireNonNull(reference, "reference must not be null"));
        if (resolved == null) {
            throw new IllegalArgumentException("unknown verification criteria: " + reference.storageValue());
        }
        return resolved;
    }

    public VerificationCriteria resolve(
            VerificationTemplateKey templateKey,
            VerificationCriteria.Reference criteriaReference
    ) {
        VerificationTemplate template = requireTemplate(templateKey);
        if (!template.criteriaReference().equals(criteriaReference)) {
            throw new IllegalArgumentException("criteria is not the exact reference pinned by the template");
        }
        VerificationCriteria resolved = requireCriteria(criteriaReference);
        if (!resolved.templateKey().equals(templateKey)) {
            throw new IllegalArgumentException("criteria is not bound to the verification template");
        }
        return resolved;
    }

    private static Map<VerificationTemplateKey, VerificationTemplate> uniqueTemplates(
            List<VerificationTemplate> values
    ) {
        Objects.requireNonNull(values, "templates must not be null");
        Map<VerificationTemplateKey, VerificationTemplate> result = new HashMap<>();
        values.forEach(value -> {
            Objects.requireNonNull(value, "template must not be null");
            if (result.put(value.key(), value) != null) {
                throw new IllegalArgumentException("duplicate verification template key: " + value.key().value());
            }
        });
        return Map.copyOf(result);
    }

    private static Map<VerificationCriteria.Reference, VerificationCriteria> uniqueCriteria(
            List<VerificationCriteria> values
    ) {
        Objects.requireNonNull(values, "criteria must not be null");
        Map<VerificationCriteria.Reference, VerificationCriteria> result = new HashMap<>();
        values.forEach(value -> {
            Objects.requireNonNull(value, "criteria must not be null");
            if (result.put(value.reference(), value) != null) {
                throw new IllegalArgumentException(
                        "duplicate verification criteria reference: " + value.reference().storageValue()
                );
            }
        });
        return Map.copyOf(result);
    }
}

package com.allog.verification.template;

import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.template.domain.VerificationTemplate;
import com.allog.verification.template.domain.VerificationTemplateKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationTemplateCatalogTest {

    @Test
    void exposesOnePhotoOnlyPilotWithExactV1Criteria() {
        VerificationTemplateCatalog catalog = new VerificationTemplateCatalog();
        VerificationTemplate template = catalog.requireTemplate(VerificationTemplateCatalog.MEAL_PHOTO_RECORD);
        VerificationCriteria criteria = catalog.resolve(template.key(), template.criteriaReference());

        assertEquals("식사 사진 기록", template.displayName());
        assertEquals(VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1, criteria.reference());
        assertEquals(Set.of(VerificationCriteria.MediaModality.PHOTO), criteria.supportedMedia());
        assertEquals(4, criteria.requiredObservations().size());
        assertTrue(criteria.evidenceRequirements().contains("assessable meal or food scene"));
        assertTrue(criteria.evidenceRequirements().contains("Do not infer consumption"));
        assertFalse(Stream.of(criteria.providerContract().getClass().getRecordComponents())
                .anyMatch(component -> component.getName().equals("templateKey")));
    }

    @Test
    void exactLookupNeverFallsForwardToFutureVersion() {
        VerificationTemplateKey key = new VerificationTemplateKey("TEST_TEMPLATE");
        VerificationCriteria.Reference v1 = new VerificationCriteria.Reference("test", 1);
        VerificationCriteria.Reference v2 = new VerificationCriteria.Reference("test", 2);
        VerificationTemplateCatalog catalog = new VerificationTemplateCatalog(
                List.of(new VerificationTemplate(key, "test", v1)),
                List.of(criteria(key, v1), criteria(key, v2))
        );

        assertEquals(v1, catalog.resolve(key, v1).reference());
        assertThrows(IllegalArgumentException.class, () -> catalog.resolve(key, v2));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.requireCriteria(new VerificationCriteria.Reference("test", 3))
        );
    }

    @Test
    void rejectsDuplicateMissingAndMismatchedCatalogEntries() {
        VerificationTemplateKey key = new VerificationTemplateKey("TEST_TEMPLATE");
        VerificationTemplateKey other = new VerificationTemplateKey("OTHER_TEMPLATE");
        VerificationCriteria.Reference reference = new VerificationCriteria.Reference("test", 1);
        VerificationTemplate template = new VerificationTemplate(key, "test", reference);
        VerificationCriteria criteria = criteria(key, reference);

        assertThrows(
                IllegalArgumentException.class,
                () -> new VerificationTemplateCatalog(List.of(template, template), List.of(criteria))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerificationTemplateCatalog(List.of(template), List.of(criteria, criteria))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerificationTemplateCatalog(List.of(template), List.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerificationTemplateCatalog(List.of(template), List.of(criteria(other, reference)))
        );
    }

    private VerificationCriteria criteria(
            VerificationTemplateKey key,
            VerificationCriteria.Reference reference
    ) {
        return new VerificationCriteria(
                reference,
                key,
                Set.of(VerificationCriteria.MediaModality.PHOTO),
                Set.of(VerificationCriteria.ObservationType.TARGET_EVIDENCE_VISIBLE),
                "test evidence"
        );
    }
}

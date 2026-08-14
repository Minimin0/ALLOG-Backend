package com.allog.verification.template.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerificationTemplateKeyTest {

    @Test
    void normalizesValidKeyAndAcceptsMaximumLength() {
        assertEquals("MEAL_PHOTO_RECORD", new VerificationTemplateKey(" meal_photo_record ").value());
        assertEquals(64, new VerificationTemplateKey("A".repeat(64)).value().length());
    }

    @Test
    void rejectsInvalidFormatAndLength() {
        assertThrows(IllegalArgumentException.class, () -> new VerificationTemplateKey("1_MEAL"));
        assertThrows(IllegalArgumentException.class, () -> new VerificationTemplateKey("MEAL-PHOTO"));
        assertThrows(IllegalArgumentException.class, () -> new VerificationTemplateKey("A".repeat(65)));
        assertThrows(NullPointerException.class, () -> new VerificationTemplateKey(null));
    }
}

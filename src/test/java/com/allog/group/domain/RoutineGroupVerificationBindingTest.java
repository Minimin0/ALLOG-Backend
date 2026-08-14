package com.allog.group.domain;

import com.allog.routine.domain.RoutineDefinition;
import com.allog.user.domain.User;
import com.allog.verification.template.VerificationTemplateCatalog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutineGroupVerificationBindingTest {

    @Test
    void legacyGroupHasNoVerificationBinding() {
        RoutineGroup group = group(false);

        assertFalse(group.hasVerificationBinding());
        assertNull(group.getVerificationTemplateKey());
        assertNull(group.getVerificationCriteriaReference());
    }

    @Test
    void aiGroupPinsExactTemplateAndCriteriaWithoutMutationOperation() {
        RoutineGroup group = group(true);

        assertTrue(group.hasVerificationBinding());
        assertEquals(VerificationTemplateCatalog.MEAL_PHOTO_RECORD, group.getVerificationTemplateKey());
        assertEquals(VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1, group.getVerificationCriteriaReference());
        assertFalse(Arrays.stream(RoutineGroup.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .anyMatch(name -> name.startsWith("setVerification") || name.startsWith("bindVerification")));
    }

    private RoutineGroup group(boolean bound) {
        RoutineDefinition definition = new RoutineDefinition("meal", null);
        User user = User.create();
        if (bound) {
            VerificationTemplateCatalog catalog = new VerificationTemplateCatalog();
            return new RoutineGroup(
                    definition,
                    user,
                    "meal group",
                    GroupVisibility.PUBLIC,
                    RoutineGroupStatus.DRAFT,
                    5,
                    1,
                    catalog.requireTemplate(VerificationTemplateCatalog.MEAL_PHOTO_RECORD)
            );
        }
        return new RoutineGroup(
                definition,
                user,
                "meal group",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.DRAFT,
                5,
                1
        );
    }
}

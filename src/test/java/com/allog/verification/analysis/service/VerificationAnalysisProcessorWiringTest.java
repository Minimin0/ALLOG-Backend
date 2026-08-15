package com.allog.verification.analysis.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The processor is the switch between "analysis code exists" and "analysis actually runs", so both
 * sides of that switch are pinned here. Neither case reaches Anthropic: enabling only builds the
 * adapter, and nothing drives the worker.
 */
class VerificationAnalysisProcessorWiringTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class WhenProviderDisabled {

        @Autowired
        private Optional<VerificationAnalysisProcessor> processor;

        @Test
        void registersNoProcessor() {
            assertTrue(processor.isEmpty());
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "allog.verification.analysis.anthropic.enabled=true",
            "allog.verification.analysis.anthropic.api-key=wiring-test-only",
            "allog.verification.analysis.anthropic.model=wiring-test-model"
    })
    @ActiveProfiles("test")
    class WhenProviderEnabled {

        @Autowired
        private Optional<VerificationAnalysisProcessor> processor;

        @Test
        void registersTheDecisionProcessorBoundToTheProvider() {
            assertInstanceOf(VerificationAnalysisDecisionProcessor.class, processor.orElseThrow());
        }
    }
}

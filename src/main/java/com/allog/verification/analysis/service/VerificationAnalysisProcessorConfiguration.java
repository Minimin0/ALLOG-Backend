package com.allog.verification.analysis.service;

import com.allog.verification.analysis.controller.VerificationAnalysisOperationsProperties;
import com.allog.verification.storage.VerificationMediaStorage;
import com.allog.verification.template.VerificationTemplateCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the decision processor onto the provider, under the same switch that registers the provider
 * itself - a processor without a provider could never run, and a provider without a processor is what
 * the worker already reports as PROCESSOR_UNAVAILABLE. Disabled by default.
 *
 * <p>Registering this bean does not start anything on its own: nothing calls
 * {@link VerificationAnalysisWorker#processNext()} yet, so activation still needs a driver.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VerificationAnalysisOperationsProperties.class)
public class VerificationAnalysisProcessorConfiguration {

    @Bean
    @ConditionalOnProperty(name = "allog.verification.analysis.anthropic.enabled", havingValue = "true")
    VerificationAnalysisProcessor verificationAnalysisDecisionProcessor(
            VerificationAnalysisInputLoader inputLoader,
            VerificationMediaStorage storage,
            VerificationAnalysisProvider provider,
            VerificationTemplateCatalog catalog
    ) {
        return new VerificationAnalysisDecisionProcessor(
                new VerificationAnalysisMediaProcessor(inputLoader, storage, provider, catalog)
        );
    }
}

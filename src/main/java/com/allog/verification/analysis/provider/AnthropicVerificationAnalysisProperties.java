package com.allog.verification.analysis.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Technical configuration for the Anthropic provider adapter. No product policy lives here.
 * The API key is supplied by the environment and is never logged or persisted.
 */
@ConfigurationProperties("allog.verification.analysis.anthropic")
public record AnthropicVerificationAnalysisProperties(
        boolean enabled,
        String apiKey,
        String model,
        Duration requestTimeout
) {

    /**
     * Bounded default. Kept an order of magnitude below the PT5M analysis processing timeout so a
     * slow provider call cannot outlive its own claim and be recovered as stale while still running.
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAXIMUM_MODEL_LENGTH = 100;

    public AnthropicVerificationAnalysisProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    }

    public void validateEnabledConfiguration() {
        if (!enabled) {
            return;
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("anthropic verification analysis apiKey is required when enabled");
        }
        if (model.isBlank()) {
            throw new IllegalStateException("anthropic verification analysis model is required when enabled");
        }
        if (model.length() > MAXIMUM_MODEL_LENGTH) {
            throw new IllegalStateException("anthropic verification analysis model must be at most 100 characters");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalStateException("anthropic verification analysis requestTimeout must be positive");
        }
    }
}

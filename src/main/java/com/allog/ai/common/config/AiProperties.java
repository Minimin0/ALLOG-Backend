package com.allog.ai.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ai")
public record AiProperties(OpenAi openai, Coach coach) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    public AiProperties {
        openai = openai == null ? new OpenAi("") : openai;
        coach = coach == null ? new Coach("", DEFAULT_TIMEOUT) : coach;
    }

    public record OpenAi(String apiKey) {
        public OpenAi {
            apiKey = apiKey == null ? "" : apiKey.trim();
        }
    }

    public record Coach(String model, Duration timeout) {
        public Coach {
            model = model == null ? "" : model.trim();
            timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("ai.coach.timeout must be positive");
            }
        }
    }

    public boolean coachAvailable() {
        return !openai.apiKey().isBlank() && !coach.model().isBlank();
    }
}

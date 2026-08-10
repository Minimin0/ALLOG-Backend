package com.allog.ai.coaching.provider;

import java.util.Objects;

public class AiProviderException extends RuntimeException {

    private final Category category;

    public AiProviderException(Category category, String message) {
        super(message);
        this.category = Objects.requireNonNull(category, "category must not be null");
    }

    public AiProviderException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category, "category must not be null");
    }

    public Category category() {
        return category;
    }

    public enum Category {
        UNAVAILABLE,
        TIMEOUT,
        CONNECTION,
        HTTP,
        MALFORMED_RESPONSE,
        VALIDATION,
        UNEXPECTED
    }
}

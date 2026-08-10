package com.allog.ai.coaching.dto;

public record AiCoachText(String title, String message) {

    public static final int MAX_TITLE_LENGTH = 80;
    public static final int MAX_MESSAGE_LENGTH = 220;

    public AiCoachText {
        title = requireText(title, "title", MAX_TITLE_LENGTH);
        message = requireText(message, "message", MAX_MESSAGE_LENGTH);
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
        }
        return trimmed;
    }
}

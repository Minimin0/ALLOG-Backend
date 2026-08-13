package com.allog.routine.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record RoutineKey(String value) {

    public static final int MAX_LENGTH = 64;
    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public RoutineKey {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "routine key must start with A-Z and contain only A-Z, 0-9, or _ up to 64 characters"
            );
        }
    }
}

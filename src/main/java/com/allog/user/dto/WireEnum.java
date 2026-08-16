package com.allog.user.dto;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Translates between the uppercase enum names the domain and database use and the lower_snake_case
 * the API speaks. Kept here so the domain enums stay free of wire concerns.
 */
final class WireEnum {

    private WireEnum() {
    }

    static String toWire(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    static <E extends Enum<E>> E fromWire(Class<E> type, String value, String fieldName) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new InvalidFieldException(fieldName, "must be one of: " + allowed(type));
        }
    }

    private static <E extends Enum<E>> String allowed(Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(WireEnum::toWire)
                .collect(Collectors.joining(", "));
    }
}

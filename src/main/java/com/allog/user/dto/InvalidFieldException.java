package com.allog.user.dto;

/**
 * A field that parsed as JSON but is not a value this contract accepts, such as an enum outside the
 * allowed set. The reason names the rule, never the rejected value.
 */
public class InvalidFieldException extends RuntimeException {

    private final String fieldName;
    private final String reason;

    public InvalidFieldException(String fieldName, String reason) {
        super("invalid request field");
        this.fieldName = fieldName;
        this.reason = reason;
    }

    public String fieldName() {
        return fieldName;
    }

    public String reason() {
        return reason;
    }
}

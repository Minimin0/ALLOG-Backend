package com.allog.user.dto;

/**
 * Raised while binding a request that carried a property the contract does not define.
 *
 * <p>Only the field name is kept. The value is deliberately dropped: an unknown property is
 * unvalidated client input and must not reach a log or an error body.
 */
public class UnknownJsonFieldException extends RuntimeException {

    private final String fieldName;

    public UnknownJsonFieldException(String fieldName) {
        super("unknown request field");
        this.fieldName = fieldName;
    }

    public String fieldName() {
        return fieldName;
    }
}

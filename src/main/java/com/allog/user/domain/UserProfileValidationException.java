package com.allog.user.domain;

/**
 * A profile or onboarding value the domain refuses, raised only for rules a client can act on.
 *
 * <p>Extends {@link IllegalArgumentException} so callers that already treat a bad argument as a bad
 * argument keep working, but it is a distinct type so the API layer can answer 400 for exactly these
 * and nothing else - a stray {@code IllegalArgumentException} from a library is a server fault, not
 * a rejected request.
 *
 * <p>Carries the rule that was broken and never the value that broke it: every field here is
 * personal data, and an error body is the last place it should surface.
 */
public class UserProfileValidationException extends IllegalArgumentException {

    private final String fieldName;

    public UserProfileValidationException(String fieldName, String reason) {
        super(reason);
        this.fieldName = fieldName;
    }

    public String fieldName() {
        return fieldName;
    }

    public String reason() {
        return getMessage();
    }
}

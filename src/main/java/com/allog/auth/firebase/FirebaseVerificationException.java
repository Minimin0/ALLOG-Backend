package com.allog.auth.firebase;

public class FirebaseVerificationException extends RuntimeException {

    public enum Reason {
        INVALID_TOKEN,
        UNAVAILABLE
    }

    private final Reason reason;

    public FirebaseVerificationException(Reason reason, String message) {
        this(reason, message, null);
    }

    public FirebaseVerificationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

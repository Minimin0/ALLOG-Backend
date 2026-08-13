package com.allog.verification.service;

public final class VerificationMediaCommandException extends RuntimeException {

    public enum Reason {
        INVALID_SIZE,
        MEDIA_TOO_LARGE,
        UNSUPPORTED_CONTENT_TYPE,
        METADATA_CONFLICT,
        MEDIA_NOT_BOUND,
        MEDIA_NOT_UPLOADED,
        BINDING_MISMATCH,
        SIZE_MISMATCH,
        CONTENT_TYPE_MISMATCH
    }

    private final Reason reason;

    public VerificationMediaCommandException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

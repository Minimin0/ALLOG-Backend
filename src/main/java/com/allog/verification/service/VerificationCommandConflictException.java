package com.allog.verification.service;

public final class VerificationCommandConflictException extends RuntimeException {

    public VerificationCommandConflictException(String message) {
        super(message);
    }
}

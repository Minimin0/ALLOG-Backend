package com.allog.verification.service;

public final class VerificationNotFoundException extends RuntimeException {

    public VerificationNotFoundException(Long verificationId) {
        super("verification not found: " + verificationId);
    }
}

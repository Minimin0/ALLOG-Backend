package com.allog.verification.analysis.domain;

public enum VerificationAnalysisFailureCode {
    TIMEOUT,
    NETWORK,
    RATE_LIMITED,
    PROVIDER_5XX,
    AUTHENTICATION,
    BAD_REQUEST,
    INVALID_RESPONSE,
    INTERRUPTED
}

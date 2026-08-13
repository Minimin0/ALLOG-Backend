package com.allog.verification.analysis.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

final class VerificationAnalysisTime {

    private VerificationAnalysisTime() {
    }

    static Instant snapshot(Clock clock) {
        return Objects.requireNonNull(
                Objects.requireNonNull(clock, "clock must not be null").instant(),
                "clock instant must not be null"
        ).truncatedTo(ChronoUnit.MICROS);
    }
}

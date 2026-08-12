package com.allog.allogbe.routineverification.duplicate;

public record HashedSubmission(Long verificationId, Long userId, PerceptualHash hash) {
}

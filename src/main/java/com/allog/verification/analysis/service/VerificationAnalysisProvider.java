package com.allog.verification.analysis.service;

import com.allog.verification.storage.VerificationMediaStorage;

@FunctionalInterface
public interface VerificationAnalysisProvider {

    VerificationAnalysisProcessor.Outcome analyze(
            VerificationAnalysisInput input,
            VerificationMediaStorage.StoredMedia media
    );
}

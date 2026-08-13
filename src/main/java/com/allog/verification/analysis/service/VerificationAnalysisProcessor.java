package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;

import java.util.Objects;

@FunctionalInterface
public interface VerificationAnalysisProcessor {

    Outcome process(VerificationAnalysisClaim claim);

    sealed interface Outcome permits Success, Failure {
    }

    record Success(VerificationAnalysisSuccessResult result) implements Outcome {

        public Success {
            Objects.requireNonNull(result, "result must not be null");
        }
    }

    record Failure(VerificationAnalysisFailureCode failureCode) implements Outcome {

        public Failure {
            Objects.requireNonNull(failureCode, "failureCode must not be null");
        }
    }
}

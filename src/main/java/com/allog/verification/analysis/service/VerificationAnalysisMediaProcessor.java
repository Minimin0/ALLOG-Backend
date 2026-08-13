package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.storage.VerificationMediaProperties;
import com.allog.verification.storage.VerificationMediaStorage;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

public final class VerificationAnalysisMediaProcessor implements VerificationAnalysisProcessor {

    private final VerificationAnalysisInputLoader inputLoader;
    private final VerificationMediaStorage storage;
    private final VerificationAnalysisProvider provider;

    public VerificationAnalysisMediaProcessor(
            VerificationAnalysisInputLoader inputLoader,
            VerificationMediaStorage storage,
            VerificationAnalysisProvider provider
    ) {
        this.inputLoader = Objects.requireNonNull(inputLoader);
        this.storage = Objects.requireNonNull(storage);
        this.provider = Objects.requireNonNull(provider);
    }

    @Override
    public Outcome process(VerificationAnalysisClaim claim) {
        requireNoTransaction("input load");
        final VerificationAnalysisInput input;
        try {
            input = inputLoader.load(claim);
        } catch (VerificationAnalysisInputLoader.LoadException exception) {
            return new Failure(VerificationAnalysisFailureCode.BAD_REQUEST);
        }

        requireNoTransaction("media acquisition");
        final VerificationMediaStorage.StoredMedia media;
        try {
            media = storage.acquire(input.objectKey(), input.sizeBytes());
        } catch (VerificationMediaStorage.StorageException exception) {
            if (exception.reason() == VerificationMediaStorage.StorageException.Reason.UNAVAILABLE) {
                return new Failure(VerificationAnalysisFailureCode.NETWORK);
            }
            throw exception;
        }

        requireNoTransaction("media validation");
        try {
            requireValidMedia(input, media);
        } catch (IllegalArgumentException exception) {
            return new Failure(VerificationAnalysisFailureCode.BAD_REQUEST);
        }

        requireNoTransaction("provider execution");
        return Objects.requireNonNull(
                provider.analyze(input, media),
                "provider outcome must not be null"
        );
    }

    private void requireValidMedia(
            VerificationAnalysisInput input,
            VerificationMediaStorage.StoredMedia media
    ) {
        Objects.requireNonNull(media, "media must not be null");
        final String actualContentType;
        try {
            actualContentType = VerificationMediaProperties.normalizeContentType(media.contentType());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("stored media content type is invalid", exception);
        }
        if (!input.objectKey().equals(media.objectKey())
                || input.sizeBytes() != media.contentLength()
                || input.sizeBytes() != media.bodyLength()
                || !input.contentType().equals(actualContentType)) {
            throw new IllegalArgumentException("stored media does not match the analysis input");
        }
    }

    private void requireNoTransaction(String operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " must run without a DB transaction");
        }
    }
}

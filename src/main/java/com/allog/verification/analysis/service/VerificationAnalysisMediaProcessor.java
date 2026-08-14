package com.allog.verification.analysis.service;

import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.media.PhotoMetadataSanitizer;
import com.allog.verification.storage.VerificationMediaProperties;
import com.allog.verification.storage.VerificationMediaStorage;
import com.allog.verification.template.VerificationTemplateCatalog;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

public final class VerificationAnalysisMediaProcessor {

    private final VerificationAnalysisInputLoader inputLoader;
    private final VerificationMediaStorage storage;
    private final VerificationAnalysisProvider provider;
    private final VerificationTemplateCatalog catalog;

    public VerificationAnalysisMediaProcessor(
            VerificationAnalysisInputLoader inputLoader,
            VerificationMediaStorage storage,
            VerificationAnalysisProvider provider,
            VerificationTemplateCatalog catalog
    ) {
        this.inputLoader = Objects.requireNonNull(inputLoader);
        this.storage = Objects.requireNonNull(storage);
        this.provider = Objects.requireNonNull(provider);
        this.catalog = Objects.requireNonNull(catalog);
    }

    public Outcome process(VerificationAnalysisClaim claim) {
        requireNoTransaction("input load");
        final VerificationAnalysisInput input;
        try {
            input = inputLoader.load(claim);
        } catch (VerificationAnalysisInputLoader.LoadException exception) {
            return new Failure(VerificationAnalysisFailureCode.BAD_REQUEST);
        }
        final VerificationCriteria criteria;
        try {
            criteria = catalog.requireCriteria(input.criteriaReference());
        } catch (IllegalArgumentException exception) {
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
        final VerificationCriteria.MediaModality modality;
        try {
            requireValidMedia(input, media);
            modality = mediaModality(input.contentType());
            if (!criteria.supportedMedia().contains(modality)) {
                throw new IllegalArgumentException("criteria does not support the stored media modality");
            }
        } catch (IllegalArgumentException exception) {
            return new Failure(VerificationAnalysisFailureCode.BAD_REQUEST);
        }

        // Privacy hard gate: provider-bound bytes must never carry EXIF/GPS. Fail closed - anything the
        // sanitizer cannot prove clean is rejected instead of forwarded.
        requireNoTransaction("media sanitization");
        final byte[] sanitizedContent;
        try {
            sanitizedContent = PhotoMetadataSanitizer.strip(input.contentType(), media.content());
        } catch (PhotoMetadataSanitizer.SanitizationException exception) {
            return new Failure(VerificationAnalysisFailureCode.BAD_REQUEST);
        }

        requireNoTransaction("provider execution");
        final VerificationAnalysisProvider.Result result;
        try {
            result = Objects.requireNonNull(
                    provider.analyze(
                            criteria.providerContract(),
                            new VerificationAnalysisProvider.Evidence(
                                    modality,
                                    input.contentType(),
                                    sanitizedContent
                            )
                    ),
                    "provider result must not be null"
            );
        } catch (VerificationAnalysisProvider.ProviderException exception) {
            return new Failure(exception.failureCode());
        }
        if (result.observation().reasonCode()
                == VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
                && criteria.requiredObservations().stream().anyMatch(type -> !result.observation().hasValue(type))) {
            return new Failure(VerificationAnalysisFailureCode.INVALID_RESPONSE);
        }
        return new Observed(criteria, result);
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

    private VerificationCriteria.MediaModality mediaModality(String contentType) {
        if (contentType.startsWith("image/")) {
            return VerificationCriteria.MediaModality.PHOTO;
        }
        if (contentType.startsWith("video/")) {
            return VerificationCriteria.MediaModality.VIDEO;
        }
        throw new IllegalArgumentException("stored media modality is unsupported");
    }

    public sealed interface Outcome permits Observed, Failure {
    }

    public record Observed(
            VerificationCriteria criteria,
            VerificationAnalysisProvider.Result providerResult
    ) implements Outcome {

        public Observed {
            Objects.requireNonNull(criteria, "criteria must not be null");
            Objects.requireNonNull(providerResult, "providerResult must not be null");
        }
    }

    public record Failure(VerificationAnalysisFailureCode failureCode) implements Outcome {

        public Failure {
            Objects.requireNonNull(failureCode, "failureCode must not be null");
        }
    }
}

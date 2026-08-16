package com.allog.verification.service;

import com.allog.verification.domain.Verification;
import com.allog.verification.media.PhotoMetadataSanitizer;
import com.allog.verification.storage.VerificationMediaStorage;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class VerificationMediaSubmissionService {

    private final VerificationCommandService commandService;
    private final VerificationMediaStorage storage;
    private final VerificationMediaPolicy policy;

    public VerificationMediaSubmissionService(
            VerificationCommandService commandService,
            VerificationMediaStorage storage,
            VerificationMediaPolicy policy
    ) {
        this.commandService = Objects.requireNonNull(commandService);
        this.storage = Objects.requireNonNull(storage);
        this.policy = Objects.requireNonNull(policy);
    }

    public VerificationSubmissionResult submitCurrent(Long groupId, Long currentUserId) {
        policy.requireEnabled();
        VerificationCommandService.SubmissionTarget target = commandService.prepareCurrentSubmission(
                groupId,
                currentUserId
        );
        if (target.idempotentResult() != null) {
            return VerificationSubmissionResult.from(target.idempotentResult());
        }

        final VerificationMediaStorage.StoredMediaInspection inspection;
        try {
            inspection = storage.inspect(target.objectKey());
        } catch (VerificationMediaStorage.StorageException exception) {
            if (exception.reason() == VerificationMediaStorage.StorageException.Reason.NOT_FOUND) {
                throw new VerificationMediaCommandException(
                        VerificationMediaCommandException.Reason.MEDIA_NOT_UPLOADED,
                        "verification media has not been uploaded"
                );
            }
            throw exception;
        }
        policy.requireInspection(
                target.objectKey(),
                target.contentType(),
                target.expectedSizeBytes(),
                inspection
        );
        Verification verification = commandService.submitInspectedCurrent(
                groupId,
                currentUserId,
                sanitizeInPlace(inspection)
        );
        return VerificationSubmissionResult.from(verification);
    }

    /**
     * Strips EXIF/GPS from the uploaded object and writes the result back over the same key, so the
     * metadata never reaches permanent storage. Runs outside the submission transaction and returns the
     * inspection describing what is actually stored afterwards.
     */
    private VerificationMediaStorage.StoredMediaInspection sanitizeInPlace(
            VerificationMediaStorage.StoredMediaInspection inspection
    ) {
        if (!PhotoMetadataSanitizer.supports(inspection.contentType())) {
            // ponytail: video carries GPS atoms of its own; sanitizing those is a separate step.
            return inspection;
        }
        VerificationMediaStorage.StoredMedia stored = storage.acquire(
                inspection.objectKey(),
                inspection.contentLength()
        );
        final byte[] sanitized;
        try {
            sanitized = PhotoMetadataSanitizer.strip(inspection.contentType(), stored.content());
        } catch (PhotoMetadataSanitizer.SanitizationException exception) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.CONTENT_TYPE_MISMATCH,
                    "stored media is not a readable image of the declared content type"
            );
        }
        if (sanitized.length == stored.bodyLength()) {
            return inspection;
        }
        storage.overwrite(inspection.objectKey(), inspection.contentType(), sanitized);
        return new VerificationMediaStorage.StoredMediaInspection(
                inspection.objectKey(),
                sanitized.length,
                inspection.contentType()
        );
    }
}

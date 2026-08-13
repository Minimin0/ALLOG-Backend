package com.allog.verification.service;

import com.allog.verification.domain.Verification;
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
        Verification verification = commandService.submitInspectedCurrent(groupId, currentUserId, inspection);
        return VerificationSubmissionResult.from(verification);
    }
}

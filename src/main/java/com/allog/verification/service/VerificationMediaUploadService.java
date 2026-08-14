package com.allog.verification.service;

import com.allog.verification.storage.VerificationMediaStorage;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

@Service
public class VerificationMediaUploadService {

    private final VerificationCommandService commandService;
    private final VerificationMediaStorage storage;
    private final VerificationMediaPolicy policy;
    private final Clock clock;

    public VerificationMediaUploadService(
            VerificationCommandService commandService,
            VerificationMediaStorage storage,
            VerificationMediaPolicy policy,
            Clock clock
    ) {
        this.commandService = Objects.requireNonNull(commandService);
        this.storage = Objects.requireNonNull(storage);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    public VerificationMediaStorage.UploadGrant issueCurrentUpload(
            Long groupId,
            Long currentUserId,
            String contentType,
            long sizeBytes
    ) {
        policy.requireEnabled();
        String allowedContentType = policy.requireAllowedContentType(contentType);
        policy.requireAllowedSize(sizeBytes);
        VerificationCommandService.UploadTarget target = commandService.prepareCurrentUpload(
                groupId,
                currentUserId,
                allowedContentType,
                sizeBytes
        );
        if (!clock.instant().isBefore(target.expiresAt())) {
            throw new VerificationCommandConflictException(
                    "current verification deadline closed while issuing the upload grant"
            );
        }
        return storage.issueUpload(
                target.objectKey(),
                target.contentType(),
                target.expectedSizeBytes(),
                target.expiresAt()
        );
    }
}

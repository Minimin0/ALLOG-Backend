package com.allog.verification.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.service.VerificationCommandService;
import com.allog.verification.service.VerificationCurrentResult;
import com.allog.verification.service.VerificationMediaSubmissionService;
import com.allog.verification.service.VerificationMediaUploadService;
import com.allog.verification.service.VerificationSubmissionResult;
import com.allog.verification.storage.VerificationMediaStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/me/groups/{groupId}/verifications/current")
public class VerificationController {

    private final VerificationCommandService commandService;
    private final VerificationMediaUploadService uploadService;
    private final VerificationMediaSubmissionService submissionService;

    public VerificationController(
            VerificationCommandService commandService,
            VerificationMediaUploadService uploadService,
            VerificationMediaSubmissionService submissionService
    ) {
        this.commandService = commandService;
        this.uploadService = uploadService;
        this.submissionService = submissionService;
    }

    @PostMapping
    public CurrentResponse current(
            @Positive @PathVariable Long groupId,
            @AuthenticationPrincipal AllogPrincipal principal
    ) {
        return CurrentResponse.from(commandService.createOrGetCurrentResult(groupId, principal.userId()));
    }

    @PostMapping("/upload-intent")
    public ResponseEntity<UploadIntentResponse> uploadIntent(
            @Positive @PathVariable Long groupId,
            @AuthenticationPrincipal AllogPrincipal principal,
            @Valid @RequestBody UploadIntentRequest request
    ) {
        VerificationMediaStorage.UploadGrant grant = uploadService.issueCurrentUpload(
                groupId,
                principal.userId(),
                request.contentType(),
                request.sizeBytes()
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(UploadIntentResponse.from(grant));
    }

    @PostMapping("/submit")
    public SubmissionResponse submit(
            @Positive @PathVariable Long groupId,
            @AuthenticationPrincipal AllogPrincipal principal
    ) {
        return SubmissionResponse.from(submissionService.submitCurrent(groupId, principal.userId()));
    }

    public record UploadIntentRequest(@NotBlank String contentType, @Positive long sizeBytes) {
    }

    public record CurrentResponse(
            Long verificationId,
            LocalDate scheduledDate,
            VerificationStatus status,
            Instant submissionDeadline
    ) {
        static CurrentResponse from(VerificationCurrentResult result) {
            return new CurrentResponse(
                    result.verificationId(),
                    result.scheduledDate(),
                    result.status(),
                    result.submissionDeadline()
            );
        }
    }

    public record UploadIntentResponse(
            String method,
            URI uploadUrl,
            Map<String, List<String>> requiredHeaders,
            Instant expiresAt
    ) {
        static UploadIntentResponse from(VerificationMediaStorage.UploadGrant grant) {
            return new UploadIntentResponse(
                    grant.method(),
                    grant.uri(),
                    grant.requiredHeaders(),
                    grant.expiresAt()
            );
        }
    }

    public record SubmissionResponse(
            Long verificationId,
            LocalDate scheduledDate,
            VerificationStatus status,
            Instant submittedAt
    ) {
        static SubmissionResponse from(VerificationSubmissionResult result) {
            return new SubmissionResponse(
                    result.verificationId(),
                    result.scheduledDate(),
                    result.status(),
                    result.submittedAt()
            );
        }
    }
}

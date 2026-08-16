package com.allog.verification.analysis.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.verification.analysis.service.VerificationAnalysisClaimService;
import com.allog.verification.analysis.service.VerificationAnalysisWorker;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/**
 * Hand controls for the analysis worker, so the first real provider runs happen one at a time under
 * a person's eye instead of on a timer. Each call processes at most one analysis, which is also at
 * most one paid provider request.
 *
 * <p>With no provider configured the worker answers PROCESSOR_UNAVAILABLE and nothing is spent.
 */
@RestController
@RequestMapping("/api/v1/admin/verification-analysis")
public class VerificationAnalysisOperationsController {

    private final VerificationAnalysisWorker worker;
    private final VerificationAnalysisClaimService claimService;
    private final VerificationAnalysisOperationsProperties properties;

    public VerificationAnalysisOperationsController(
            VerificationAnalysisWorker worker,
            VerificationAnalysisClaimService claimService,
            VerificationAnalysisOperationsProperties properties
    ) {
        this.worker = Objects.requireNonNull(worker);
        this.claimService = Objects.requireNonNull(claimService);
        this.properties = Objects.requireNonNull(properties);
    }

    @PostMapping("/process-next")
    public ProcessNextResponse processNext(@AuthenticationPrincipal AllogPrincipal principal) {
        requireOperator(principal);
        return new ProcessNextResponse(worker.processNext());
    }

    /** Requeues one analysis whose attempt died mid-flight, so a crashed run is not stuck forever. */
    @PostMapping("/recover-stale")
    public RecoverStaleResponse recoverStale(@AuthenticationPrincipal AllogPrincipal principal) {
        requireOperator(principal);
        return new RecoverStaleResponse(claimService.recoverNextStaleProcessing());
    }

    private void requireOperator(AllogPrincipal principal) {
        if (principal == null || !properties.isOperator(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    public record ProcessNextResponse(VerificationAnalysisWorker.ExecutionResult result) {
    }

    public record RecoverStaleResponse(boolean recovered) {
    }
}

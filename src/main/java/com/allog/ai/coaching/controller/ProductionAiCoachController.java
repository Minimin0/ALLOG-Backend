package com.allog.ai.coaching.controller;

import com.allog.ai.coaching.production.ProductionAiCoachApplicationService;
import com.allog.auth.security.AllogPrincipal;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
public class ProductionAiCoachController {

    private final ProductionAiCoachApplicationService applicationService;

    public ProductionAiCoachController(ProductionAiCoachApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping("/{groupId}/ai-coach")
    public ProductionAiCoachResponse generate(
            @Positive @PathVariable Long groupId,
            @AuthenticationPrincipal AllogPrincipal principal
    ) {
        return ProductionAiCoachResponse.from(applicationService.generateFor(groupId, principal.userId()));
    }
}

package com.allog.ai.coaching.controller;

import com.allog.ai.coaching.dto.AiCoachResult;
import com.allog.ai.coaching.service.AiCoachApplicationService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/v1/dev/ai-coach")
public class AiCoachPreviewController {

    private final AiCoachApplicationService applicationService;

    public AiCoachPreviewController(AiCoachApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping("/preview")
    public AiCoachResponse preview(@Valid @RequestBody AiCoachPreviewRequest request) {
        AiCoachResult result = applicationService.generate(request.challengeName(), request.toProgressInput());
        return AiCoachResponse.from(result);
    }
}

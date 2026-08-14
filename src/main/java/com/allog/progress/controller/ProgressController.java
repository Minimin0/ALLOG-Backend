package com.allog.progress.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.progress.dto.ProgressResponse;
import com.allog.progress.service.ProgressReadService;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/groups")
public class ProgressController {

    private final ProgressReadService readService;

    public ProgressController(ProgressReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/{groupId}/progress")
    public ProgressResponse getProgress(
            @Positive @PathVariable Long groupId,
            @AuthenticationPrincipal AllogPrincipal principal
    ) {
        return readService.read(groupId, principal.userId());
    }
}

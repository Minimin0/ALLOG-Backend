package com.allog.user.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.user.dto.UserStatsResponse;
import com.allog.user.service.UserStatsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Read-only. There is no endpoint anywhere that changes a heart balance - hearts move only as a side
 * effect of something the member actually did, decided by the backend.
 */
@RestController
@RequestMapping("/api/v1/users/me")
public class UserStatsController {

    private final UserStatsService statsService;

    public UserStatsController(UserStatsService statsService) {
        this.statsService = Objects.requireNonNull(statsService);
    }

    @GetMapping("/stats")
    public UserStatsResponse stats(@AuthenticationPrincipal AllogPrincipal principal) {
        return statsService.read(principal.userId());
    }
}

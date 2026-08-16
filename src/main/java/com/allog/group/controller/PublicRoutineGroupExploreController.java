package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.group.dto.PublicRoutineGroupsResponse;
import com.allog.group.service.PublicRoutineGroupExploreService;
import java.util.Objects;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
public class PublicRoutineGroupExploreController {
    private final PublicRoutineGroupExploreService exploreService;

    public PublicRoutineGroupExploreController(PublicRoutineGroupExploreService exploreService) {
        this.exploreService = Objects.requireNonNull(exploreService);
    }

    @GetMapping
    public PublicRoutineGroupsResponse explore(@AuthenticationPrincipal AllogPrincipal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        return exploreService.explore();
    }
}

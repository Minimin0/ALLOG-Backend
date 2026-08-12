package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.group.dto.MyGroupsResponse;
import com.allog.group.service.MyGroupQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/groups")
public class MyGroupController {

    private final MyGroupQueryService queryService;

    public MyGroupController(MyGroupQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public MyGroupsResponse getMyGroups(
            @AuthenticationPrincipal AllogPrincipal principal,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(50) @RequestParam(defaultValue = "20") int size
    ) {
        return queryService.readMyGroups(principal.userId(), page, size);
    }
}

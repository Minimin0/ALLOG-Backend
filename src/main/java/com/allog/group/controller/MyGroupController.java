package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.group.dto.CreateRoutineGroupRequest;
import com.allog.group.dto.MyGroupDetailResponse;
import com.allog.group.dto.MyGroupsResponse;
import com.allog.group.dto.RoutineGroupCreatedResponse;
import com.allog.group.service.MyGroupNotFoundException;
import com.allog.group.service.MyGroupQueryService;
import com.allog.group.service.RoutineDefinitionNotFoundException;
import com.allog.group.service.RoutineGroupCreationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/groups")
public class MyGroupController {

    private final MyGroupQueryService queryService;
    private final RoutineGroupCreationService creationService;

    public MyGroupController(MyGroupQueryService queryService, RoutineGroupCreationService creationService) {
        this.queryService = queryService;
        this.creationService = creationService;
    }

    @PostMapping
    public ResponseEntity<RoutineGroupCreatedResponse> createGroup(
            @AuthenticationPrincipal AllogPrincipal principal,
            @Valid @RequestBody CreateRoutineGroupRequest request
    ) {
        Long groupId = creationService.create(principal.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new RoutineGroupCreatedResponse(groupId));
    }

    @GetMapping
    public MyGroupsResponse getMyGroups(
            @AuthenticationPrincipal AllogPrincipal principal,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(50) @RequestParam(defaultValue = "20") int size
    ) {
        return queryService.readMyGroups(principal.userId(), page, size);
    }

    @GetMapping("/{groupId}")
    public MyGroupDetailResponse getMyGroup(
            @AuthenticationPrincipal AllogPrincipal principal,
            @Positive @PathVariable Long groupId
    ) {
        return queryService.readMyGroup(principal.userId(), groupId);
    }

    @ExceptionHandler({MyGroupNotFoundException.class, RoutineDefinitionNotFoundException.class})
    ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    /** Domain and value-object invariants (unapproved template key, invalid schedule) reject the request. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> invalidRequest(IllegalArgumentException ignored) {
        return ResponseEntity.badRequest().build();
    }
}

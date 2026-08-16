package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.group.dto.CreateRoutineGroupRequest;
import com.allog.group.dto.MyGroupDetailResponse;
import com.allog.group.dto.MyGroupsResponse;
import com.allog.group.dto.RoutineGroupCreatedResponse;
import com.allog.group.service.GroupLifecycleException;
import com.allog.group.service.MembershipLifecycleService;
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
    private final MembershipLifecycleService membershipLifecycleService;

    public MyGroupController(
            MyGroupQueryService queryService,
            RoutineGroupCreationService creationService,
            MembershipLifecycleService membershipLifecycleService
    ) {
        this.queryService = queryService;
        this.creationService = creationService;
        this.membershipLifecycleService = membershipLifecycleService;
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

    /**
     * The owner closes a group that has not started. A group you do not own answers 404 here, the
     * same as everywhere else under /me.
     */
    @PostMapping("/{groupId}/cancel")
    public ResponseEntity<Void> cancelGroup(
            @AuthenticationPrincipal AllogPrincipal principal,
            @Positive @PathVariable Long groupId
    ) {
        membershipLifecycleService.cancel(groupId, principal.userId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(GroupLifecycleException.class)
    ResponseEntity<Void> lifecycleFailure(GroupLifecycleException exception) {
        HttpStatus status = switch (exception.reason()) {
            case GROUP_NOT_FOUND, MEMBERSHIP_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case OWNER_MUST_CANCEL, NOT_LEAVABLE, NOT_CANCELLABLE -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).build();
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

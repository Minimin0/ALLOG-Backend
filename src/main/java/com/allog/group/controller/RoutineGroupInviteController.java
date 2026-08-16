package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.group.dto.GroupInviteResponse;
import com.allog.group.dto.JoinGroupByInviteRequest;
import com.allog.group.service.GroupInviteException;
import com.allog.group.service.RoutineGroupJoinException;
import com.allog.group.service.RoutineGroupInviteService;
import com.allog.heart.service.InsufficientHeartsException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RoutineGroupInviteController {
    private final RoutineGroupInviteService inviteService;

    public RoutineGroupInviteController(RoutineGroupInviteService inviteService) {
        this.inviteService = Objects.requireNonNull(inviteService);
    }

    @PostMapping("/me/groups/{groupId}/invite")
    public GroupInviteResponse issue(
            @PathVariable @Positive Long groupId,
            @AuthenticationPrincipal AllogPrincipal principal
    ) {
        return inviteService.issue(groupId, principal.userId());
    }

    @PostMapping("/groups/join-by-invite")
    public ResponseEntity<Void> join(
            @Valid @RequestBody JoinGroupByInviteRequest request,
            @AuthenticationPrincipal AllogPrincipal principal
    ) {
        inviteService.joinByCode(request.code(), principal.userId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(GroupInviteException.class)
    ResponseEntity<Void> inviteFailure(GroupInviteException exception) {
        HttpStatus status = switch (exception.reason()) {
            case GROUP_NOT_FOUND, INVITE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NOT_PRIVATE -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).build();
    }

    @ExceptionHandler(InsufficientHeartsException.class)
    ResponseEntity<GroupHeartErrorResponse> insufficientHearts(InsufficientHeartsException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new GroupHeartErrorResponse("INSUFFICIENT_HEARTS"));
    }

    @ExceptionHandler(RoutineGroupJoinException.class)
    ResponseEntity<Void> joinFailure(RoutineGroupJoinException exception) {
        HttpStatus status = switch (exception.reason()) {
            case GROUP_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALREADY_JOINED, NOT_JOINABLE, GROUP_FULL, PRIVATE_GROUP_REQUIRES_INVITE -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).build();
    }
}

package com.allog.group.controller;
import com.allog.heart.service.InsufficientHeartsException;

import com.allog.auth.security.AllogPrincipal;
import com.allog.group.service.GroupLifecycleException;
import com.allog.group.service.MembershipLifecycleService;
import com.allog.group.service.RoutineGroupJoinException;
import com.allog.group.service.RoutineGroupJoinService;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lives outside the /me namespace: the caller is joining a group that is not theirs yet.
 */
@RestController
@RequestMapping("/api/v1/groups")
public class RoutineGroupJoinController {

    private final RoutineGroupJoinService joinService;
    private final MembershipLifecycleService membershipLifecycleService;

    public RoutineGroupJoinController(
            RoutineGroupJoinService joinService,
            MembershipLifecycleService membershipLifecycleService
    ) {
        this.joinService = joinService;
        this.membershipLifecycleService = membershipLifecycleService;
    }

    @PostMapping("/{groupId}/join")
    public ResponseEntity<Void> join(
            @Positive @PathVariable Long groupId,
            @AuthenticationPrincipal AllogPrincipal principal
    ) {
        joinService.join(groupId, principal.userId());
        return ResponseEntity.noContent().build();
    }

    /** Leaving is only for a group that has not started; repeating it is a no-op, not a failure. */
    @PostMapping("/{groupId}/leave")
    public ResponseEntity<Void> leave(
            @Positive @PathVariable Long groupId,
            @AuthenticationPrincipal AllogPrincipal principal
    ) {
        membershipLifecycleService.leave(groupId, principal.userId());
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

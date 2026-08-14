package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
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

    public RoutineGroupJoinController(RoutineGroupJoinService joinService) {
        this.joinService = joinService;
    }

    @PostMapping("/{groupId}/join")
    public ResponseEntity<Void> join(
            @Positive @PathVariable Long groupId,
            @AuthenticationPrincipal AllogPrincipal principal
    ) {
        joinService.join(groupId, principal.userId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(RoutineGroupJoinException.class)
    ResponseEntity<Void> joinFailure(RoutineGroupJoinException exception) {
        HttpStatus status = switch (exception.reason()) {
            case GROUP_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALREADY_JOINED, NOT_JOINABLE, GROUP_FULL -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).build();
    }
}

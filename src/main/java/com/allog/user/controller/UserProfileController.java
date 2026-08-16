package com.allog.user.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.user.dto.CreateUserProfileRequest;
import com.allog.user.dto.PatchUserProfileRequest;
import com.allog.user.dto.UserProfileResponse;
import com.allog.user.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * The authenticated user's own profile.
 *
 * <p>The principal is the only authority on whose profile this is; no request body carries a user id.
 * Note that {@code POST} does not create a user - authentication already did that on first request -
 * it creates the profile and onboarding for the user who is already signed in.
 */
@RestController
@RequestMapping("/api/v1")
public class UserProfileController {

    private final UserProfileService profileService;

    public UserProfileController(UserProfileService profileService) {
        this.profileService = Objects.requireNonNull(profileService);
    }

    @GetMapping("/users/me")
    public UserProfileResponse me(@AuthenticationPrincipal AllogPrincipal principal) {
        return profileService.read(principal.userId());
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfileResponse create(
            @AuthenticationPrincipal AllogPrincipal principal,
            @Valid @RequestBody CreateUserProfileRequest request
    ) {
        return profileService.create(principal.userId(), request);
    }

    @PatchMapping("/users/me")
    public UserProfileResponse patch(
            @AuthenticationPrincipal AllogPrincipal principal,
            @RequestBody PatchUserProfileRequest request
    ) {
        return profileService.patch(principal.userId(), request);
    }
}

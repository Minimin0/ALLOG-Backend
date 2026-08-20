package com.allog.auth.controller;

import com.allog.auth.application.LocalAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LocalAuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(LocalAuthService authService, LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public LocalAuthService.AuthResult signup(@Valid @RequestBody AuthRequest request) {
        return authService.signup(request.loginId(), request.password());
    }

    @PostMapping("/login")
    public LocalAuthService.AuthResult login(
            @Valid @RequestBody AuthRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.check(servletRequest);
        return authService.login(request.loginId(), request.password());
    }
}

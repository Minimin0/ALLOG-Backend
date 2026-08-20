package com.allog.auth.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public record AuthRequest(
        @NotBlank @Size(min = 4, max = 32) @Pattern(regexp = "[a-z0-9_]+") String loginId,
        @NotBlank @Size(min = 8, max = 72) String password
) {
    public AuthRequest {
        if (loginId != null) loginId = loginId.trim().toLowerCase(Locale.ROOT);
    }

    @AssertTrue(message = "password must be at most 72 UTF-8 bytes")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}

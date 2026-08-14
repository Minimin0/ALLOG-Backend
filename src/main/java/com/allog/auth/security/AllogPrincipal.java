package com.allog.auth.security;

import java.security.Principal;
import java.util.Objects;

public record AllogPrincipal(Long userId) implements Principal {

    public AllogPrincipal {
        Objects.requireNonNull(userId, "userId must not be null");
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}

package com.allog.auth.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

public final class AllogAuthenticationToken extends AbstractAuthenticationToken {

    private final AllogPrincipal principal;

    private AllogAuthenticationToken(AllogPrincipal principal) {
        super(List.of());
        this.principal = principal;
        super.setAuthenticated(true);
    }

    public static AllogAuthenticationToken authenticated(AllogPrincipal principal) {
        return new AllogAuthenticationToken(principal);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AllogPrincipal getPrincipal() {
        return principal;
    }
}

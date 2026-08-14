package com.allog.auth.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

public final class FirebaseBearerAuthenticationToken extends AbstractAuthenticationToken {

    private final Object principal;
    private String credentials;

    private FirebaseBearerAuthenticationToken(Object principal, String credentials, boolean authenticated) {
        super(List.of());
        this.principal = principal;
        this.credentials = credentials;
        super.setAuthenticated(authenticated);
    }

    public static FirebaseBearerAuthenticationToken unauthenticated(String idToken) {
        return new FirebaseBearerAuthenticationToken(null, idToken, false);
    }

    public static FirebaseBearerAuthenticationToken authenticated(AllogPrincipal principal) {
        return new FirebaseBearerAuthenticationToken(principal, null, true);
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        credentials = null;
    }
}

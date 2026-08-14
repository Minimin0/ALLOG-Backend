package com.allog.auth.security;

import com.allog.auth.application.AuthenticatedUserResolver;
import com.allog.auth.firebase.FirebaseIdTokenVerifier;
import com.allog.auth.firebase.FirebaseVerificationException;
import com.allog.auth.firebase.VerifiedFirebaseToken;
import com.allog.user.domain.IdentityProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public class FirebaseAuthenticationProvider implements AuthenticationProvider {

    private final FirebaseIdTokenVerifier tokenVerifier;
    private final AuthenticatedUserResolver userResolver;

    public FirebaseAuthenticationProvider(
            FirebaseIdTokenVerifier tokenVerifier,
            AuthenticatedUserResolver userResolver
    ) {
        this.tokenVerifier = tokenVerifier;
        this.userResolver = userResolver;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String idToken = (String) authentication.getCredentials();
        try {
            VerifiedFirebaseToken verified = tokenVerifier.verify(idToken);
            Long userId = userResolver.resolveOrCreate(IdentityProvider.FIREBASE, verified.uid());
            return FirebaseBearerAuthenticationToken.authenticated(new AllogPrincipal(userId));
        } catch (FirebaseVerificationException exception) {
            if (exception.getReason() == FirebaseVerificationException.Reason.UNAVAILABLE) {
                throw new AuthenticationServiceException("Firebase authentication is unavailable", exception);
            }
            throw new BadCredentialsException("Invalid bearer token", exception);
        } catch (DataAccessException exception) {
            throw new AuthenticationServiceException("Identity resolution is unavailable", exception);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return FirebaseBearerAuthenticationToken.class.isAssignableFrom(authentication);
    }
}

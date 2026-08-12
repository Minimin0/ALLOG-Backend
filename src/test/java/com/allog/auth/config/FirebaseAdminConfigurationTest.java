package com.allog.auth.config;

import com.allog.auth.firebase.FirebaseIdTokenVerifier;
import com.allog.auth.firebase.FirebaseVerificationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FirebaseAdminConfigurationTest {

    private final FirebaseAdminConfiguration configuration = new FirebaseAdminConfiguration();

    @Test
    void enabledFirebaseRequiresProjectIdBeforeCredentialInitialization() {
        assertThrows(
                IllegalStateException.class,
                () -> configuration.firebaseApp(new FirebaseAuthProperties(true, " "))
        );
    }

    @Test
    void disabledFirebaseVerifierFailsClosed() {
        FirebaseIdTokenVerifier verifier = configuration.unavailableFirebaseIdTokenVerifier();

        FirebaseVerificationException exception = assertThrows(
                FirebaseVerificationException.class,
                () -> verifier.verify("any-token")
        );
        assertEquals(FirebaseVerificationException.Reason.UNAVAILABLE, exception.getReason());
    }
}

package com.allog.auth.firebase;

import com.google.firebase.ErrorCode;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FirebaseAdminIdTokenVerifierTest {

    private final FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
    private final FirebaseAdminIdTokenVerifier verifier = new FirebaseAdminIdTokenVerifier(firebaseAuth);

    @Test
    void returnsOnlyVerifiedUid() throws Exception {
        FirebaseToken firebaseToken = mock(FirebaseToken.class);
        when(firebaseToken.getUid()).thenReturn("verified-uid");
        when(firebaseAuth.verifyIdToken("valid-token")).thenReturn(firebaseToken);

        assertEquals(new VerifiedFirebaseToken("verified-uid"), verifier.verify("valid-token"));
    }

    @Test
    void translatesInvalidTokenWithoutExposingSdkException() throws Exception {
        when(firebaseAuth.verifyIdToken("invalid-token")).thenThrow(new FirebaseAuthException(
                ErrorCode.INVALID_ARGUMENT,
                "sdk detail",
                null,
                null,
                AuthErrorCode.INVALID_ID_TOKEN
        ));

        FirebaseVerificationException exception = assertThrows(
                FirebaseVerificationException.class,
                () -> verifier.verify("invalid-token")
        );
        assertEquals(FirebaseVerificationException.Reason.INVALID_TOKEN, exception.getReason());
    }

    @Test
    void translatesMalformedTokenAsInvalid() throws Exception {
        when(firebaseAuth.verifyIdToken("malformed-token"))
                .thenThrow(new IllegalArgumentException("sdk detail"));

        FirebaseVerificationException exception = assertThrows(
                FirebaseVerificationException.class,
                () -> verifier.verify("malformed-token")
        );
        assertEquals(FirebaseVerificationException.Reason.INVALID_TOKEN, exception.getReason());
    }

    @Test
    void translatesCertificateFailureAsInfrastructureUnavailable() throws Exception {
        when(firebaseAuth.verifyIdToken("unavailable-token")).thenThrow(new FirebaseAuthException(
                ErrorCode.UNAVAILABLE,
                "sdk detail",
                null,
                null,
                AuthErrorCode.CERTIFICATE_FETCH_FAILED
        ));

        FirebaseVerificationException exception = assertThrows(
                FirebaseVerificationException.class,
                () -> verifier.verify("unavailable-token")
        );
        assertEquals(FirebaseVerificationException.Reason.UNAVAILABLE, exception.getReason());
    }
}

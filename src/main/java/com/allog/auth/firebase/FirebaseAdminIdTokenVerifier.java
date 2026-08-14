package com.allog.auth.firebase;

import com.google.firebase.ErrorCode;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;

public class FirebaseAdminIdTokenVerifier implements FirebaseIdTokenVerifier {

    private final FirebaseAuth firebaseAuth;

    public FirebaseAdminIdTokenVerifier(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    public VerifiedFirebaseToken verify(String idToken) {
        try {
            return new VerifiedFirebaseToken(firebaseAuth.verifyIdToken(idToken).getUid());
        } catch (FirebaseAuthException exception) {
            throw translate(exception);
        } catch (IllegalArgumentException exception) {
            throw new FirebaseVerificationException(
                    FirebaseVerificationException.Reason.INVALID_TOKEN,
                    "Invalid Firebase ID token",
                    exception
            );
        } catch (IllegalStateException exception) {
            throw new FirebaseVerificationException(
                    FirebaseVerificationException.Reason.UNAVAILABLE,
                    "Firebase authentication is unavailable",
                    exception
            );
        }
    }

    private FirebaseVerificationException translate(FirebaseAuthException exception) {
        AuthErrorCode authError = exception.getAuthErrorCode();
        ErrorCode error = exception.getErrorCode();
        boolean invalid = authError == AuthErrorCode.EXPIRED_ID_TOKEN
                || authError == AuthErrorCode.INVALID_ID_TOKEN
                || authError == AuthErrorCode.REVOKED_ID_TOKEN
                || authError == AuthErrorCode.TENANT_ID_MISMATCH
                || authError == AuthErrorCode.USER_DISABLED
                || error == ErrorCode.INVALID_ARGUMENT
                || error == ErrorCode.UNAUTHENTICATED;

        return new FirebaseVerificationException(
                invalid
                        ? FirebaseVerificationException.Reason.INVALID_TOKEN
                        : FirebaseVerificationException.Reason.UNAVAILABLE,
                invalid ? "Invalid Firebase ID token" : "Firebase authentication is unavailable",
                exception
        );
    }
}

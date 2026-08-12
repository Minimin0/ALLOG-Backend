package com.allog.auth.firebase;

public interface FirebaseIdTokenVerifier {

    VerifiedFirebaseToken verify(String idToken);
}

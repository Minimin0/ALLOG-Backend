package com.allog.auth.firebase;

public record VerifiedFirebaseToken(String uid) {

    public VerifiedFirebaseToken {
        if (uid == null || uid.isBlank()) {
            throw new FirebaseVerificationException(
                    FirebaseVerificationException.Reason.INVALID_TOKEN,
                    "Verified Firebase UID must not be blank"
            );
        }
    }
}

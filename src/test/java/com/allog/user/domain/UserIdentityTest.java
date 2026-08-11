package com.allog.user.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class UserIdentityTest {

    @Test
    void requiresUserProviderAndNonBlankSubject() {
        User user = User.create();

        assertThrows(NullPointerException.class,
                () -> new UserIdentity(null, IdentityProvider.FIREBASE, "firebase-user-123"));
        assertThrows(NullPointerException.class,
                () -> new UserIdentity(user, null, "firebase-user-123"));
        assertThrows(IllegalArgumentException.class,
                () -> new UserIdentity(user, IdentityProvider.FIREBASE, null));
        assertThrows(IllegalArgumentException.class,
                () -> new UserIdentity(user, IdentityProvider.FIREBASE, " "));
    }
}

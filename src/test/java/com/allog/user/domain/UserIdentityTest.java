package com.allog.user.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class UserIdentityTest {

    @Test
    void requiresUserProviderAndNonBlankSubject() {
        User user = User.create();

        assertThrows(NullPointerException.class,
                () -> UserIdentity.local(null, "local-user-123", "hash"));
        assertThrows(IllegalArgumentException.class,
                () -> UserIdentity.local(user, null, "hash"));
        assertThrows(IllegalArgumentException.class,
                () -> UserIdentity.local(user, " ", "hash"));
        assertThrows(IllegalArgumentException.class,
                () -> UserIdentity.local(user, "local-user-123", " "));
    }
}

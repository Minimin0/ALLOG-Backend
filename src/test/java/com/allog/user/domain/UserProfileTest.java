package com.allog.user.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserProfileTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 16);

    @Test
    void trimsNicknameAndKeepsOptionalFieldsAbsent() {
        UserProfile profile = UserProfile.create(User.create(), "  민지  ", null, null, TODAY);

        assertEquals("민지", profile.getNickname());
        assertNull(profile.getGender());
        assertNull(profile.getBirthDate());
    }

    @Test
    void rejectsNullBlankAndOverlongNickname() {
        assertThrows(IllegalArgumentException.class,
                () -> UserProfile.create(User.create(), null, null, null, TODAY));
        assertThrows(IllegalArgumentException.class,
                () -> UserProfile.create(User.create(), "   ", null, null, TODAY));
        assertThrows(IllegalArgumentException.class,
                () -> UserProfile.create(User.create(), "가".repeat(21), null, null, TODAY));
    }

    @Test
    void acceptsNicknameAtTheLengthBoundary() {
        UserProfile profile = UserProfile.create(User.create(), "가".repeat(20), null, null, TODAY);

        assertEquals(20, profile.getNickname().length());
    }

    @Test
    void rejectsFutureBirthDateOnCreateAndUpdate() {
        assertThrows(IllegalArgumentException.class,
                () -> UserProfile.create(User.create(), "민지", null, TODAY.plusDays(1), TODAY));

        UserProfile profile = UserProfile.create(User.create(), "민지", null, null, TODAY);
        assertThrows(IllegalArgumentException.class, () -> profile.updateBirthDate(TODAY.plusDays(1), TODAY));
    }

    @Test
    void acceptsTodayAsBirthDate() {
        UserProfile profile = UserProfile.create(User.create(), "민지", null, TODAY, TODAY);

        assertEquals(TODAY, profile.getBirthDate());
    }

    @Test
    void clearsOptionalFieldsWithNull() {
        UserProfile profile = UserProfile.create(User.create(), "민지", Gender.FEMALE, TODAY.minusYears(20), TODAY);

        profile.updateGender(null);
        profile.updateBirthDate(null, TODAY);

        assertNull(profile.getGender());
        assertNull(profile.getBirthDate());
    }

    @Test
    void exceptionMessagesDoNotEchoTheRejectedValue() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> UserProfile.create(User.create(), "민지".repeat(11), null, null, TODAY));

        org.junit.jupiter.api.Assertions.assertFalse(thrown.getMessage().contains("민지"));
    }
}

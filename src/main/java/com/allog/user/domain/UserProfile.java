package com.allog.user.domain;

import com.allog.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.util.Objects;

/**
 * What a member shows to other people. Separate from {@link User} because identity exists from the
 * first authenticated request while a profile only exists after onboarding, and because deleting
 * one row is how this user's personal data gets removed without disturbing the identity every
 * membership and verification points at.
 *
 * <p>No {@code toString}: every field here is personal data and must not reach a log by accident.
 */
@Entity
@Table(
        name = "user_profile",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_profile_user",
                columnNames = "user_id"
        )
)
public class UserProfile extends BaseTimeEntity {

    public static final int NICKNAME_MAX_LENGTH = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "nickname", nullable = false, length = NICKNAME_MAX_LENGTH)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 16)
    private Gender gender;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    protected UserProfile() {
    }

    private UserProfile(User user, String nickname, Gender gender, LocalDate birthDate, LocalDate today) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.nickname = requireNickname(nickname);
        this.gender = gender;
        this.birthDate = requireNotFuture(birthDate, today);
    }

    public static UserProfile create(
            User user,
            String nickname,
            Gender gender,
            LocalDate birthDate,
            LocalDate today
    ) {
        return new UserProfile(user, nickname, gender, birthDate, today);
    }

    public void updateNickname(String value) {
        this.nickname = requireNickname(value);
    }

    /** Null clears it: gender is optional, and a member may withdraw it after giving it. */
    public void updateGender(Gender value) {
        this.gender = value;
    }

    public void updateBirthDate(LocalDate value, LocalDate today) {
        this.birthDate = requireNotFuture(value, today);
    }

    /**
     * Trimmed first, so " " is rejected as blank and "  name  " is stored the way it reads. The
     * message names the rule and never the value.
     */
    private static String requireNickname(String value) {
        if (value == null) {
            throw new IllegalArgumentException("nickname must not be null");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("nickname must not be blank");
        }
        if (trimmed.length() > NICKNAME_MAX_LENGTH) {
            throw new IllegalArgumentException("nickname must not exceed " + NICKNAME_MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    private static LocalDate requireNotFuture(LocalDate value, LocalDate today) {
        Objects.requireNonNull(today, "today must not be null");
        if (value != null && value.isAfter(today)) {
            throw new IllegalArgumentException("birthDate must not be in the future");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getNickname() {
        return nickname;
    }

    public Gender getGender() {
        return gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }
}

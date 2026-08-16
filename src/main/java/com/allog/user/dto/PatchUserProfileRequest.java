package com.allog.user.dto;

import com.allog.user.domain.Gender;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.time.LocalDate;

/**
 * Partial update where an absent field and an explicit null mean different things: absent leaves the
 * value alone, null clears it where clearing is allowed.
 *
 * <p>Each setter records that the property appeared. Jackson calls a setter for an explicit null but
 * not for an absent property, which is the only reliable way to tell the two apart -
 * {@code Optional<T>} cannot, since both collapse to the same empty value.
 */
public class PatchUserProfileRequest {

    private boolean nicknamePresent;
    private String nickname;

    private boolean genderPresent;
    private Gender gender;

    private boolean birthDatePresent;
    private LocalDate birthDate;

    private boolean onboardingPresent;
    private PatchUserOnboardingRequest onboarding;

    @JsonAnySetter
    void rejectUnknown(String fieldName, Object ignoredValue) {
        throw new UnknownJsonFieldException(fieldName);
    }

    @JsonSetter("nickname")
    void setNickname(String value) {
        this.nicknamePresent = true;
        this.nickname = value;
    }

    @JsonSetter("gender")
    void setGender(String value) {
        this.genderPresent = true;
        // Null stays null: gender is clearable. A non-null value outside the contract fails here.
        this.gender = WireEnum.fromWire(Gender.class, value, "gender");
    }

    @JsonSetter("birthDate")
    void setBirthDate(LocalDate value) {
        this.birthDatePresent = true;
        this.birthDate = value;
    }

    @JsonSetter("onboarding")
    void setOnboarding(PatchUserOnboardingRequest value) {
        this.onboardingPresent = true;
        this.onboarding = value;
    }

    public boolean isNicknamePresent() {
        return nicknamePresent;
    }

    /** Null here means the client sent {@code "nickname": null}, which is not a legal clear. */
    public String getNickname() {
        return nickname;
    }

    public boolean isGenderPresent() {
        return genderPresent;
    }

    public Gender getGender() {
        return gender;
    }

    public boolean isBirthDatePresent() {
        return birthDatePresent;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public boolean isOnboardingPresent() {
        return onboardingPresent;
    }

    public PatchUserOnboardingRequest getOnboarding() {
        return onboarding;
    }
}

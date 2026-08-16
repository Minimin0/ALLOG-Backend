package com.allog.user.dto;

import com.allog.user.domain.Gender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.time.LocalDate;

/**
 * A class rather than a record so {@link JsonAnySetter} can reject unknown properties during
 * binding. Absent and explicit-null mean the same thing on create - the field is simply not set -
 * so no presence tracking is needed here; {@link PatchUserProfileRequest} is where they differ.
 */
public class CreateUserProfileRequest {

    @NotBlank
    @Size(max = 20)
    private String nickname;

    private Gender gender;

    private LocalDate birthDate;

    @NotNull
    @Valid
    private CreateUserOnboardingRequest onboarding;

    @JsonAnySetter
    void rejectUnknown(String fieldName, Object ignoredValue) {
        throw new UnknownJsonFieldException(fieldName);
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    /** Converted while binding, so an unusable enum is rejected before any service is called. */
    public void setGender(String gender) {
        this.gender = WireEnum.fromWire(Gender.class, gender, "gender");
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public void setOnboarding(CreateUserOnboardingRequest onboarding) {
        this.onboarding = onboarding;
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

    public CreateUserOnboardingRequest getOnboarding() {
        return onboarding;
    }
}

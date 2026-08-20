package com.allog.user.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.AllogAuthenticationToken;
import com.allog.user.domain.UserProfileValidationException;
import com.allog.user.dto.PatchUserProfileRequest;
import com.allog.user.dto.UserProfileResponse;
import com.allog.user.service.ProfileAlreadyExistsException;
import com.allog.user.service.ProfileNotFoundException;
import com.allog.user.service.UserProfileService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileControllerTest {

    private static final Long USER_ID = 11L;
    private static final String ME = "/api/v1/users/me";
    private static final String USERS = "/api/v1/users";

    private static final String VALID_CREATE = """
            {
              "nickname": "민지",
              "gender": "female",
              "birthDate": "2000-07-30",
              "onboarding": {
                "interestRoutines": ["hydration", "exercise"],
                "coachStyle": "supportive",
                "averageSleepHours": 7.0,
                "exerciseDaysPerWeek": 3,
                "mealsPerDay": 3,
                "preferredGroupDurationDays": 7
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService profileService;

    @Test
    void returnsTheProfileInWireEnumForm() throws Exception {
        when(profileService.read(USER_ID)).thenReturn(response());

        mockMvc.perform(authenticated(get(ME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(11))
                .andExpect(jsonPath("$.nickname").value("민지"))
                .andExpect(jsonPath("$.gender").value("female"))
                .andExpect(jsonPath("$.onboarding.coachStyle").value("supportive"))
                .andExpect(jsonPath("$.onboarding.interestRoutines[0]").value("hydration"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.heightCm").doesNotExist())
                .andExpect(jsonPath("$.weightKg").doesNotExist())
                .andExpect(jsonPath("$.profileImageUrl").doesNotExist())
                .andExpect(jsonPath("$.stats").doesNotExist());
    }

    @Test
    void answers404BeforeOnboardingIsDone() throws Exception {
        when(profileService.read(USER_ID)).thenThrow(new ProfileNotFoundException());

        mockMvc.perform(authenticated(get(ME)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROFILE_NOT_FOUND"));
    }

    @Test
    void createsTheProfileForTheAuthenticatedUser() throws Exception {
        when(profileService.create(eq(USER_ID), any())).thenReturn(response());

        mockMvc.perform(authenticated(post(USERS)).contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(11));
    }

    @Test
    void answers409WhenTheProfileAlreadyExists() throws Exception {
        when(profileService.create(eq(USER_ID), any())).thenThrow(new ProfileAlreadyExistsException());

        mockMvc.perform(authenticated(post(USERS)).contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PROFILE_ALREADY_EXISTS"));
    }

    @Test
    void rejectsAnUnknownTopLevelFieldWithoutReachingTheService() throws Exception {
        String body = """
                {"nickname": "민지", "hack": true}
                """;

        mockMvc.perform(authenticated(post(USERS)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"))
                .andExpect(jsonPath("$.error.details[0].field").value("hack"));

        verifyNoInteractions(profileService);
    }

    @Test
    void rejectsAnUnknownNestedOnboardingField() throws Exception {
        String body = """
                {
                  "nickname": "민지",
                  "onboarding": {
                    "interestRoutines": ["meal"],
                    "coachStyle": "supportive",
                    "averageSleepHours": 7.0,
                    "exerciseDaysPerWeek": 3,
                    "mealsPerDay": 3,
                    "preferredGroupDurationDays": 7,
                    "hack": true
                  }
                }
                """;

        mockMvc.perform(authenticated(post(USERS)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"))
                .andExpect(jsonPath("$.error.details[0].field").value("onboarding.hack"));

        verifyNoInteractions(profileService);
    }

    @Test
    void rejectsAnUnknownFieldOnPatchToo() throws Exception {
        mockMvc.perform(authenticated(patch(ME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hack\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));

        verifyNoInteractions(profileService);
    }

    @Test
    void rejectsAnEnumOutsideTheContractWithoutEchoingTheValue() throws Exception {
        String body = VALID_CREATE.replace("\"supportive\"", "\"bossy\"");
        when(profileService.create(eq(USER_ID), any())).thenReturn(response());

        String payload = mockMvc.perform(authenticated(post(USERS))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(payload.contains("bossy"));
    }

    @Test
    void rejectsAMissingRequiredFieldOnCreate() throws Exception {
        String body = VALID_CREATE.replace("\"nickname\": \"민지\",", "");

        mockMvc.perform(authenticated(post(USERS)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /** The heart of the PATCH contract: absent, explicit null and value must arrive as three states. */
    @Test
    void bindsAbsentExplicitNullAndValueAsThreeDistinctStates() throws Exception {
        when(profileService.patch(eq(USER_ID), any())).thenReturn(response());
        ArgumentCaptor<PatchUserProfileRequest> captured = ArgumentCaptor.forClass(PatchUserProfileRequest.class);

        mockMvc.perform(authenticated(patch(ME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"수정\", \"gender\": null}"))
                .andExpect(status().isOk());

        verify(profileService).patch(eq(USER_ID), captured.capture());
        PatchUserProfileRequest request = captured.getValue();

        assertTrue(request.isNicknamePresent());
        assertEquals("수정", request.getNickname());

        assertTrue(request.isGenderPresent(), "explicit null must count as present");
        assertNull(request.getGender(), "explicit null must clear");

        assertFalse(request.isBirthDatePresent(), "absent must not count as present");
        assertFalse(request.isOnboardingPresent());
    }

    @Test
    void bindsNestedOnboardingPresenceIndependently() throws Exception {
        when(profileService.patch(eq(USER_ID), any())).thenReturn(response());
        ArgumentCaptor<PatchUserProfileRequest> captured = ArgumentCaptor.forClass(PatchUserProfileRequest.class);

        mockMvc.perform(authenticated(patch(ME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboarding\": {\"preferredGroupDurationDays\": 14}}"))
                .andExpect(status().isOk());

        verify(profileService).patch(eq(USER_ID), captured.capture());
        var onboarding = captured.getValue().getOnboarding();

        assertTrue(captured.getValue().isOnboardingPresent());
        assertTrue(onboarding.isPreferredGroupDurationDaysPresent());
        assertEquals(14, onboarding.getPreferredGroupDurationDays());
        assertFalse(onboarding.isCoachStylePresent());
        assertFalse(onboarding.isInterestRoutinesPresent());
    }

    @Test
    void anEmptyPatchBodyChangesNothing() throws Exception {
        when(profileService.patch(eq(USER_ID), any())).thenReturn(response());
        ArgumentCaptor<PatchUserProfileRequest> captured = ArgumentCaptor.forClass(PatchUserProfileRequest.class);

        mockMvc.perform(authenticated(patch(ME)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(profileService).patch(eq(USER_ID), captured.capture());
        PatchUserProfileRequest request = captured.getValue();

        assertFalse(request.isNicknamePresent());
        assertFalse(request.isGenderPresent());
        assertFalse(request.isBirthDatePresent());
        assertFalse(request.isOnboardingPresent());
    }

    @Test
    void unauthenticatedRequestsNeverReachTheService() throws Exception {
        mockMvc.perform(get(ME)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(ME).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(profileService);
    }

    private static UserProfileResponse response() {
        return new UserProfileResponse(
                USER_ID,
                "민지",
                "female",
                LocalDate.of(2000, 7, 30),
                new UserProfileResponse.Onboarding(
                        List.of("hydration", "exercise"),
                        "supportive",
                        new BigDecimal("7.0"),
                        3, 3, 7));
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.with(authentication(AllogAuthenticationToken.authenticated(
                new AllogPrincipal(USER_ID))));
    }

    @Test
    void rejectsDuplicateInterestCategoriesOnCreate() throws Exception {
        String body = VALID_CREATE.replace(
                "[\"hydration\", \"exercise\"]", "[\"meal\", \"meal\"]");

        String payload = mockMvc.perform(authenticated(post(USERS))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("onboarding.interestRoutines"))
                .andExpect(jsonPath("$.error.details[0].reason").value("must not contain duplicate categories"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(payload.contains("meal"), "the rejected value must not be echoed");
        verifyNoInteractions(profileService);
    }

    @Test
    void rejectsDuplicateInterestCategoriesOnPatch() throws Exception {
        mockMvc.perform(authenticated(patch(ME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboarding\": {\"interestRoutines\": [\"meal\", \"meal\"]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("onboarding.interestRoutines"));

        verifyNoInteractions(profileService);
    }

    @Test
    void distinctInterestCategoriesStillPass() throws Exception {
        when(profileService.create(eq(USER_ID), any())).thenReturn(response());

        mockMvc.perform(authenticated(post(USERS))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isCreated());
    }

    /** A domain rejection reaches the client as a field-scoped 400, carrying the rule and no value. */
    @Test
    void domainValidationBecomesAFieldScopedBadRequest() throws Exception {
        when(profileService.create(eq(USER_ID), any()))
                .thenThrow(new UserProfileValidationException("birthDate", "must not be in the future"));

        String payload = mockMvc.perform(authenticated(post(USERS))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("birthDate"))
                .andExpect(jsonPath("$.error.details[0].reason").value("must not be in the future"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(payload.contains("2000-07-30"));
    }

    /**
     * A plain IllegalArgumentException is a server fault, not a rejected request. The advice must not
     * catch it: it propagates out of the handler chain, which Boot renders as a 500 rather than the
     * client seeing 400 with an internal message.
     */
    @Test
    void aGenericIllegalArgumentIsNotTreatedAsAValidationFailure() {
        when(profileService.read(USER_ID))
                .thenThrow(new IllegalArgumentException("internal detail that must not leak"));

        ServletException thrown = assertThrows(ServletException.class,
                () -> mockMvc.perform(authenticated(get(ME))));

        assertInstanceOf(IllegalArgumentException.class, thrown.getRootCause(),
                "a generic bad argument must escape the validation advice, not become a 400");
    }
}

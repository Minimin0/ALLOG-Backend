package com.allog.user.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.heart.service.HeartWalletNotFoundException;
import com.allog.user.dto.UserStatsResponse;
import com.allog.user.service.ProfileNotFoundException;
import com.allog.user.service.UserStatsService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "allog.auth.firebase.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserStatsControllerTest {

    private static final Long USER_ID = 42L;
    private static final String STATS = "/api/v1/users/me/stats";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserStatsService statsService;

    @Test
    void returnsHeartsRewardPointsAndSuccessfulRoutines() throws Exception {
        when(statsService.read(USER_ID)).thenReturn(new UserStatsResponse(3, 40L, 2L));

        mockMvc.perform(authenticated(get(STATS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hearts").value(3))
                .andExpect(jsonPath("$.rewardPoints").value(40))
                .andExpect(jsonPath("$.successfulRoutines").value(2));
    }

    @Test
    void reportsZeroSuccessfulRoutinesWhenNoMembershipCompleted() throws Exception {
        when(statsService.read(USER_ID)).thenReturn(new UserStatsResponse(3, 0L, 0L));

        mockMvc.perform(authenticated(get(STATS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successfulRoutines").value(0));
    }

    @Test
    void answers404BeforeOnboardingIsDone() throws Exception {
        when(statsService.read(USER_ID)).thenThrow(new ProfileNotFoundException());

        mockMvc.perform(authenticated(get(STATS)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROFILE_NOT_FOUND"));
    }

    /**
     * A profile with no wallet is a server fault. It must not be answered as a client error, and its
     * message must not reach the client.
     */
    @Test
    void aMissingWalletIsNotDisguisedAsAClientError() {
        when(statsService.read(USER_ID)).thenThrow(new HeartWalletNotFoundException(USER_ID));

        ServletException thrown = assertThrows(ServletException.class,
                () -> mockMvc.perform(authenticated(get(STATS))));

        assertInstanceOf(HeartWalletNotFoundException.class, thrown.getRootCause());
    }

    @Test
    void unauthenticatedRequestsNeverReachTheService() throws Exception {
        mockMvc.perform(get(STATS)).andExpect(status().isUnauthorized());

        verifyNoInteractions(statsService);
    }

    /** Hearts are never client-writable: the read endpoint is the only heart surface that exists. */
    @Test
    void thereIsNoEndpointForChangingHearts() throws Exception {
        mockMvc.perform(authenticated(post("/api/v1/users/me/hearts")))
                .andExpect(status().isNotFound());
        mockMvc.perform(authenticated(post("/api/v1/hearts/grant")))
                .andExpect(status().isNotFound());
        mockMvc.perform(authenticated(post(STATS)))
                .andExpect(status().isMethodNotAllowed());
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(USER_ID))));
    }
}

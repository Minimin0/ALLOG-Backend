package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.group.service.GroupLifecycleException;
import com.allog.group.service.MembershipLifecycleService;
import com.allog.group.service.RoutineGroupJoinService;
import com.allog.heart.service.InsufficientHeartsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = "allog.auth.firebase.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GroupLifecycleControllerTest {

    private static final Long USER_ID = 7L;
    private static final Long GROUP_ID = 42L;
    private static final String LEAVE = "/api/v1/groups/42/leave";
    private static final String CANCEL = "/api/v1/me/groups/42/cancel";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MembershipLifecycleService membershipLifecycleService;
    @MockitoBean
    private RoutineGroupJoinService joinService;

    @Test
    void insufficientHeartsForJoinIsAConflictWithAMachineReadableCode() throws Exception {
        doThrow(new InsufficientHeartsException()).when(joinService).join(GROUP_ID, USER_ID);
        mockMvc.perform(authenticated(post("/api/v1/groups/42/join")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_HEARTS"));
    }

    @Test
    void leavingAnswersNoContentAndNeedsNoBody() throws Exception {
        doNothing().when(membershipLifecycleService).leave(GROUP_ID, USER_ID);

        mockMvc.perform(authenticated(post(LEAVE))).andExpect(status().isNoContent());

        verify(membershipLifecycleService).leave(GROUP_ID, USER_ID);
    }


    @Test
    void cancellingAnswersNoContentAndNeedsNoBody() throws Exception {
        doNothing().when(membershipLifecycleService).cancel(GROUP_ID, USER_ID);

        mockMvc.perform(authenticated(post(CANCEL))).andExpect(status().isNoContent());

        verify(membershipLifecycleService).cancel(GROUP_ID, USER_ID);
    }

    @ParameterizedTest
    @EnumSource(value = GroupLifecycleException.Reason.class, names = {"GROUP_NOT_FOUND", "MEMBERSHIP_NOT_FOUND"})
    void aMissingGroupOrMembershipIsNotFound(GroupLifecycleException.Reason reason) throws Exception {
        doThrow(new GroupLifecycleException(reason, "no")).when(membershipLifecycleService).leave(GROUP_ID, USER_ID);

        mockMvc.perform(authenticated(post(LEAVE))).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(value = GroupLifecycleException.Reason.class,
            names = {"OWNER_MUST_CANCEL", "NOT_LEAVABLE", "NOT_CANCELLABLE"})
    void aLifecycleRefusalIsAConflict(GroupLifecycleException.Reason reason) throws Exception {
        doThrow(new GroupLifecycleException(reason, "no")).when(membershipLifecycleService).leave(GROUP_ID, USER_ID);

        mockMvc.perform(authenticated(post(LEAVE))).andExpect(status().isConflict());
    }

    /** Under /me a group you do not own is a group you cannot see. */

    @Test
    void cancellingSomeoneElsesGroupIsNotFound() throws Exception {
        doThrow(new GroupLifecycleException(GroupLifecycleException.Reason.GROUP_NOT_FOUND, "no"))
                .when(membershipLifecycleService).cancel(GROUP_ID, USER_ID);

        mockMvc.perform(authenticated(post(CANCEL))).andExpect(status().isNotFound());
    }


    @Test
    void unauthenticatedRequestsNeverReachTheService() throws Exception {
        mockMvc.perform(post(LEAVE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(CANCEL)).andExpect(status().isUnauthorized());

        verifyNoInteractions(membershipLifecycleService);
    }

    /** Hearts are not client-writable, and lifecycle did not add a way in. */
    @Test

    void thereIsNoLifecycleEndpointThatMovesHearts() throws Exception {
        mockMvc.perform(authenticated(post("/api/v1/groups/42/refund"))).andExpect(status().isNotFound());
        mockMvc.perform(authenticated(post("/api/v1/me/groups/42/hearts"))).andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(USER_ID))));
    }
}

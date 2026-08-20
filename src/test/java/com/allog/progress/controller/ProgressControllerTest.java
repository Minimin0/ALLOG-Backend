package com.allog.progress.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.AllogAuthenticationToken;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.progress.dto.ProgressResponse;
import com.allog.progress.service.ProgressNotFoundException;
import com.allog.progress.service.ProgressReadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProgressControllerTest {

    private static final Long GROUP_ID = 42L;
    private static final Long USER_ID = 17L;
    private static final String ENDPOINT = "/api/v1/me/groups/42/progress";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProgressReadService readService;

    @Test
    void returnsActiveContractUsingOnlyAuthenticatedPrincipal() throws Exception {
        when(readService.read(GROUP_ID, USER_ID)).thenReturn(activeResponse());

        mockMvc.perform(authenticatedGet()
                        .queryParam("userId", "999999")
                        .header("X-User-Id", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(jsonPath("$.participationStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.personal.*", hasSize(10)))
                .andExpect(jsonPath("$.personal.todayScheduled").value(true))
                .andExpect(jsonPath("$.personal.completedCount").value(3))
                .andExpect(jsonPath("$.personal.certificationDeadline")
                        .value("2026-08-11T14:00:00Z"))
                .andExpect(jsonPath("$.group.*", hasSize(6)))
                .andExpect(jsonPath("$.group.eligibleMemberCount").value(2))
                .andExpect(jsonPath("$.group.groupCompletionRate").value(0.8))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.memberId").doesNotExist())
                .andExpect(jsonPath("$.verificationId").doesNotExist());

        verify(readService).read(GROUP_ID, USER_ID);
        verify(readService, never()).read(GROUP_ID, 999999L);
    }

    @Test
    void unauthenticatedRequestReturns401WithoutCallingService() throws Exception {
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());

        verifyNoInteractions(readService);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveGroupIdReturns400WithoutCallingService(long groupId) throws Exception {
        mockMvc.perform(get("/api/v1/me/groups/{groupId}/progress", groupId)
                        .with(authentication(AllogAuthenticationToken.authenticated(
                                new AllogPrincipal(USER_ID)
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        verifyNoInteractions(readService);
    }

    @ParameterizedTest
    @EnumSource(value = GroupMemberStatus.class, names = {"JOINED", "COMPLETED", "FAILED"})
    void lifecycleResponseIncludesExplicitNullFacts(GroupMemberStatus statusValue) throws Exception {
        when(readService.read(GROUP_ID, USER_ID))
                .thenReturn(new ProgressResponse(statusValue, null, null));

        mockMvc.perform(authenticatedGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participationStatus").value(statusValue.name()))
                .andExpect(jsonPath("$.personal").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.group").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void hiddenOrMissingMembershipReturnsStatusOnly404() throws Exception {
        when(readService.read(GROUP_ID, USER_ID)).thenThrow(new ProgressNotFoundException());

        mockMvc.perform(authenticatedGet())
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void invariantFailureReturns500WithoutLeakingInternalMessage() throws Exception {
        when(readService.read(GROUP_ID, USER_ID))
                .thenThrow(new IllegalStateException("schedule missing for groupId=42 userId=17"));

        mockMvc.perform(authenticatedGet())
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("groupId=42"))))
                .andExpect(content().string(not(containsString("userId=17"))));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedGet() {
        return get(ENDPOINT).with(authentication(AllogAuthenticationToken.authenticated(
                new AllogPrincipal(USER_ID)
        )));
    }

    private ProgressResponse activeResponse() {
        return new ProgressResponse(
                GroupMemberStatus.ACTIVE,
                new ProgressResponse.Personal(
                        true,
                        false,
                        false,
                        3,
                        5,
                        2,
                        1,
                        2,
                        0,
                        Instant.parse("2026-08-11T14:00:00Z")
                ),
                new ProgressResponse.Group(2, 8, 10, 0.8, 0, 1)
        );
    }
}

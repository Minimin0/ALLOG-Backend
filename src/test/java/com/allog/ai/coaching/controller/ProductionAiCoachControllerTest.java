package com.allog.ai.coaching.controller;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.production.AiCoachAccessDeniedException;
import com.allog.ai.coaching.production.AiCoachParticipationNotFoundException;
import com.allog.ai.coaching.production.ProductionAiCoachApplicationService;
import com.allog.ai.coaching.production.ProductionAiCoachResult;
import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.group.domain.GroupMemberStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
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

@SpringBootTest(properties = "allog.auth.firebase.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionAiCoachControllerTest {

    private static final Long GROUP_ID = 42L;
    private static final Long USER_ID = 17L;
    private static final String ENDPOINT = "/api/v1/groups/42/ai-coach";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductionAiCoachApplicationService applicationService;

    @Test
    void returnsActiveContractAndUsesOnlyAuthenticatedPrincipalIdentity() throws Exception {
        when(applicationService.generateFor(GROUP_ID, USER_ID)).thenReturn(result(
                GroupMemberStatus.ACTIVE,
                InsightType.DEADLINE_APPROACHING,
                RoutineState.ATTENTION,
                ActionType.OPEN_CERTIFICATION,
                "인증하기",
                GenerationType.AI
        ));

        mockMvc.perform(authenticatedGet()
                        .queryParam("userId", "999999")
                        .header("X-User-Id", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("코칭 제목"))
                .andExpect(jsonPath("$.message").value("코칭 메시지"))
                .andExpect(jsonPath("$.participationStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.insightType").value("DEADLINE_APPROACHING"))
                .andExpect(jsonPath("$.routineState").value("ATTENTION"))
                .andExpect(jsonPath("$.actionType").value("OPEN_CERTIFICATION"))
                .andExpect(jsonPath("$.actionLabel").value("인증하기"))
                .andExpect(jsonPath("$.generationType").value("AI"))
                .andExpect(jsonPath("$.userId").doesNotExist());

        verify(applicationService).generateFor(GROUP_ID, USER_ID);
        verify(applicationService, never()).generateFor(GROUP_ID, 999999L);
    }

    @Test
    void unauthenticatedRequestReturns401WithoutCallingService() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(applicationService);
    }

    @Test
    void malformedAuthenticationReturns401WithoutCallingService() throws Exception {
        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(applicationService);
    }

    @Test
    void joinedReturns200WithNullableProgressFields() throws Exception {
        when(applicationService.generateFor(GROUP_ID, USER_ID)).thenReturn(result(
                GroupMemberStatus.JOINED,
                null,
                null,
                ActionType.OPEN_GROUP,
                "그룹 현황 보기",
                GenerationType.TEMPLATE
        ));

        mockMvc.perform(authenticatedGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participationStatus").value("JOINED"))
                .andExpect(jsonPath("$.insightType").isEmpty())
                .andExpect(jsonPath("$.routineState").isEmpty());
    }

    @Test
    void completedReturns200WithCompletedRoutineState() throws Exception {
        when(applicationService.generateFor(GROUP_ID, USER_ID)).thenReturn(result(
                GroupMemberStatus.COMPLETED,
                null,
                RoutineState.COMPLETED,
                ActionType.OPEN_PROGRESS,
                "진행 현황 보기",
                GenerationType.TEMPLATE
        ));

        mockMvc.perform(authenticatedGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participationStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.insightType").isEmpty())
                .andExpect(jsonPath("$.routineState").value("COMPLETED"));
    }

    @Test
    void failedReturns200WithoutFakeProgressFields() throws Exception {
        when(applicationService.generateFor(GROUP_ID, USER_ID)).thenReturn(result(
                GroupMemberStatus.FAILED,
                null,
                null,
                ActionType.OPEN_PROGRESS,
                "진행 현황 보기",
                GenerationType.TEMPLATE
        ));

        mockMvc.perform(authenticatedGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participationStatus").value("FAILED"))
                .andExpect(jsonPath("$.insightType").isEmpty())
                .andExpect(jsonPath("$.routineState").isEmpty());
    }

    @Test
    void missingMembershipReturnsStatusOnly404() throws Exception {
        when(applicationService.generateFor(GROUP_ID, USER_ID))
                .thenThrow(new AiCoachParticipationNotFoundException(GROUP_ID, USER_ID));

        mockMvc.perform(authenticatedGet())
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void leftMembershipReturnsStatusOnly404() throws Exception {
        when(applicationService.generateFor(GROUP_ID, USER_ID))
                .thenThrow(new AiCoachAccessDeniedException(GroupMemberStatus.LEFT));

        mockMvc.perform(authenticatedGet())
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void removedMembershipReturnsStatusOnly404() throws Exception {
        when(applicationService.generateFor(GROUP_ID, USER_ID))
                .thenThrow(new AiCoachAccessDeniedException(GroupMemberStatus.REMOVED));

        mockMvc.perform(authenticatedGet())
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void invariantFailureReturns500WithoutLeakingInternalMessage() throws Exception {
        when(applicationService.generateFor(GROUP_ID, USER_ID))
                .thenThrow(new IllegalStateException("routine schedule missing for userId=17"));

        mockMvc.perform(authenticatedGet())
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("userId=17"))));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveGroupIdReturns400WithoutCallingService(long groupId) throws Exception {
        mockMvc.perform(get("/api/v1/groups/{groupId}/ai-coach", groupId)
                        .with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                                new AllogPrincipal(USER_ID)
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        verifyNoInteractions(applicationService);
    }

    private MockHttpServletRequestBuilder authenticatedGet() {
        return get(ENDPOINT).with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(USER_ID)
        )));
    }

    private ProductionAiCoachResult result(
            GroupMemberStatus status,
            InsightType insightType,
            RoutineState routineState,
            ActionType actionType,
            String actionLabel,
            GenerationType generationType
    ) {
        return new ProductionAiCoachResult(
                "코칭 제목",
                "코칭 메시지",
                status,
                insightType,
                routineState,
                actionType,
                actionLabel,
                generationType
        );
    }
}

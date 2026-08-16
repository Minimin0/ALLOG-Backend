package com.allog.verification.analysis.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.verification.analysis.service.VerificationAnalysisClaimService;
import com.allog.verification.analysis.service.VerificationAnalysisWorker;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerificationAnalysisOperationsControllerTest {

    private static final String PROCESS_NEXT = "/api/v1/admin/verification-analysis/process-next";
    private static final String RECOVER_STALE = "/api/v1/admin/verification-analysis/recover-stale";
    private static final Long OPERATOR_ID = 7L;
    private static final Long MEMBER_ID = 8L;

    @Nested
    @SpringBootTest(properties = {
            "allog.auth.firebase.enabled=false",
            "allog.verification.analysis.operations.operator-user-ids=7"
    })
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class WithConfiguredOperator {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private VerificationAnalysisWorker worker;

        @MockitoBean
        private VerificationAnalysisClaimService claimService;

        @Test
        void operatorDrivesOneAnalysisPerCall() throws Exception {
            when(worker.processNext()).thenReturn(VerificationAnalysisWorker.ExecutionResult.COMPLETED);

            mockMvc.perform(as(OPERATOR_ID, PROCESS_NEXT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("COMPLETED"));
        }

        @Test
        void operatorRecoversOneStaleAttempt() throws Exception {
            when(claimService.recoverNextStaleProcessing()).thenReturn(true);

            mockMvc.perform(as(OPERATOR_ID, RECOVER_STALE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recovered").value(true));
        }

        @Test
        void anAuthenticatedNonOperatorCannotSpendProviderBudget() throws Exception {
            mockMvc.perform(as(MEMBER_ID, PROCESS_NEXT)).andExpect(status().isForbidden());
            mockMvc.perform(as(MEMBER_ID, RECOVER_STALE)).andExpect(status().isForbidden());

            verifyNoInteractions(worker, claimService);
        }

        @Test
        void anonymousCallersAreRejectedBeforeReachingTheWorker() throws Exception {
            mockMvc.perform(post(PROCESS_NEXT)).andExpect(status().isUnauthorized());

            verifyNoInteractions(worker, claimService);
        }
    }

    /** A deployment that names no operator must close the endpoints, not open them. */
    @Test
    void noConfiguredOperatorsMeansNobodyIsAnOperator() {
        assertFalse(new VerificationAnalysisOperationsProperties(null).isOperator(OPERATOR_ID));
        assertFalse(new VerificationAnalysisOperationsProperties(Set.of()).isOperator(OPERATOR_ID));
    }

    private static MockHttpServletRequestBuilder as(Long userId, String endpoint) {
        return post(endpoint).with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(userId)
        )));
    }
}

package com.allog.verification.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.service.VerificationCommandConflictException;
import com.allog.verification.service.VerificationNotFoundException;
import com.allog.verification.service.VerificationReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "allog.auth.firebase.enabled=false",
        "allog.verification.analysis.operations.operator-user-ids=7"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VerificationReviewControllerTest {

    private static final Long OPERATOR_ID = 7L;
    private static final Long MEMBER_ID = 8L;
    private static final String APPROVE = "/api/v1/admin/verifications/42/approve";
    private static final String REJECT = "/api/v1/admin/verifications/42/reject";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerificationReviewService reviewService;

    @Test
    void operatorApprovesAHeldVerification() throws Exception {
        Verification approved = settled(VerificationStatus.APPROVED, null);
        when(reviewService.approve(42L)).thenReturn(approved);

        mockMvc.perform(as(OPERATOR_ID, APPROVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationId").value(42))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void operatorRejectsWithAReasonThatIsKept() throws Exception {
        Verification rejected = settled(VerificationStatus.REJECTED, "food is not visible");
        when(reviewService.reject(42L, "food is not visible")).thenReturn(rejected);

        mockMvc.perform(as(OPERATOR_ID, REJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"food is not visible\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNote").value("food is not visible"));

        verify(reviewService).reject(eq(42L), eq("food is not visible"));
    }

    @Test
    void anAuthenticatedNonOperatorCannotSettleVerifications() throws Exception {
        mockMvc.perform(as(MEMBER_ID, APPROVE)).andExpect(status().isForbidden());
        mockMvc.perform(as(MEMBER_ID, REJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reviewService);
    }

    @Test
    void aVerificationThatIsNotHeldCannotBeSettled() throws Exception {
        when(reviewService.approve(anyLong()))
                .thenThrow(new VerificationCommandConflictException("already settled"));

        mockMvc.perform(as(OPERATOR_ID, APPROVE)).andExpect(status().isConflict());
    }

    @Test
    void anUnknownVerificationIsNotFound() throws Exception {
        when(reviewService.approve(anyLong())).thenThrow(new VerificationNotFoundException(42L));

        mockMvc.perform(as(OPERATOR_ID, APPROVE)).andExpect(status().isNotFound());
    }

    @Test
    void aRejectionWithoutAReasonIsRefused() throws Exception {
        mockMvc.perform(as(OPERATOR_ID, REJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reviewService);
    }

    private static Verification settled(VerificationStatus status, String reviewNote) {
        Verification verification = mock(Verification.class);
        when(verification.getId()).thenReturn(42L);
        when(verification.getStatus()).thenReturn(status);
        when(verification.getReviewNote()).thenReturn(reviewNote);
        return verification;
    }

    private static MockHttpServletRequestBuilder as(Long userId, String endpoint) {
        return post(endpoint).with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(userId)
        )));
    }
}

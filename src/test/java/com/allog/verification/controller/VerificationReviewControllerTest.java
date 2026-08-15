package com.allog.verification.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.service.PendingReview;
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

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private static final String QUEUE = "/api/v1/admin/verifications/pending-review";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerificationReviewService reviewService;

    @Test
    void operatorApprovesAHeldVerification() throws Exception {
        Verification approved = settled(VerificationStatus.APPROVED, null);
        when(reviewService.approve(42L, OPERATOR_ID)).thenReturn(approved);

        mockMvc.perform(as(OPERATOR_ID, APPROVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationId").value(42))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void operatorRejectsWithAReasonThatIsKept() throws Exception {
        Verification rejected = settled(VerificationStatus.REJECTED, "food is not visible");
        when(reviewService.reject(42L, OPERATOR_ID, "food is not visible")).thenReturn(rejected);

        mockMvc.perform(as(OPERATOR_ID, REJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"food is not visible\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNote").value("food is not visible"));

        verify(reviewService).reject(eq(42L), eq(OPERATOR_ID), eq("food is not visible"));
    }

    @Test
    void anAuthenticatedNonOperatorReachesNoneOfTheAdminActions() throws Exception {
        mockMvc.perform(reading(MEMBER_ID, QUEUE)).andExpect(status().isForbidden());
        mockMvc.perform(as(MEMBER_ID, APPROVE)).andExpect(status().isForbidden());
        mockMvc.perform(as(MEMBER_ID, REJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reviewService);
    }

    @Test
    void operatorReadsTheQueueWithTheAiFeedbackAndAMediaLink() throws Exception {
        when(reviewService.reviewQueue()).thenReturn(List.of(new PendingReview(
                42L,
                8L,
                3L,
                LocalDate.parse("2026-08-11"),
                2,
                Instant.parse("2026-08-11T09:00:00Z"),
                Instant.parse("2026-08-11T10:00:00Z"),
                URI.create("https://example.invalid/photo?signed"),
                AnalysisRecommendation.REJECT_CANDIDATE,
                "OBSERVATION_COMPLETE",
                false,
                false,
                "meal-photo-record@1"
        )));

        mockMvc.perform(reading(OPERATOR_ID, QUEUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].verificationId").value(42))
                .andExpect(jsonPath("$[0].userId").value(8))
                .andExpect(jsonPath("$[0].attemptCount").value(2))
                .andExpect(jsonPath("$[0].mediaUrl").value("https://example.invalid/photo?signed"))
                .andExpect(jsonPath("$[0].recommendation").value("REJECT_CANDIDATE"))
                .andExpect(jsonPath("$[0].reasonCode").value("OBSERVATION_COMPLETE"))
                .andExpect(jsonPath("$[0].criteriaVersion").value("meal-photo-record@1"));
    }

    @Test
    void aVerificationThatIsNotHeldCannotBeSettled() throws Exception {
        when(reviewService.approve(anyLong(), anyLong()))
                .thenThrow(new VerificationCommandConflictException("already settled"));

        mockMvc.perform(as(OPERATOR_ID, APPROVE)).andExpect(status().isConflict());
    }

    @Test
    void anUnknownVerificationIsNotFound() throws Exception {
        when(reviewService.approve(anyLong(), anyLong())).thenThrow(new VerificationNotFoundException(42L));

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
        return authenticate(post(endpoint), userId);
    }

    private static MockHttpServletRequestBuilder reading(Long userId, String endpoint) {
        return authenticate(get(endpoint), userId);
    }

    private static MockHttpServletRequestBuilder authenticate(
            MockHttpServletRequestBuilder request,
            Long userId
    ) {
        return request.with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(userId)
        )));
    }
}

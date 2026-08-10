package com.allog.ai.coaching.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ai.openai.api-key=",
        "ai.coach.model="
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class AiCoachPreviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsStableContractAndTemplateWhenProviderIsUnavailable() throws Exception {
        mockMvc.perform(post("/api/v1/dev/ai-coach/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.insightType").value("DEADLINE_APPROACHING"))
                .andExpect(jsonPath("$.routineState").value("ATTENTION"))
                .andExpect(jsonPath("$.actionType").value("OPEN_CERTIFICATION"))
                .andExpect(jsonPath("$.actionLabel").value("인증하기"))
                .andExpect(jsonPath("$.generationType").value("TEMPLATE"));
    }

    @Test
    void rejectsNegativeCompletedCountWithJsonError() throws Exception {
        mockMvc.perform(post("/api/v1/dev/ai-coach/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"completedCount\": 3", "\"completedCount\": -1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."));
    }

    @Test
    void rejectsRateOutsideContract() throws Exception {
        mockMvc.perform(post("/api/v1/dev/ai-coach/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"groupCompletionRate\": 0.8", "\"groupCompletionRate\": 1.1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsPendingStatusWithoutCertificationActionOrAttentionState() throws Exception {
        mockMvc.perform(post("/api/v1/dev/ai-coach/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pendingRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("인증 결과를 확인하고 있어요"))
                .andExpect(jsonPath("$.insightType").value("VERIFICATION_PENDING"))
                .andExpect(jsonPath("$.routineState").value("GOOD"))
                .andExpect(jsonPath("$.actionType").value("OPEN_PROGRESS"))
                .andExpect(jsonPath("$.actionLabel").value("진행 현황 보기"))
                .andExpect(jsonPath("$.generationType").value("TEMPLATE"));
    }

    private String validRequest() {
        return """
                {
                  "challengeName": "물 마시기",
                  "todayScheduled": true,
                  "todayCompleted": false,
                  "todayVerificationPending": false,
                  "completedCount": 3,
                  "requiredCompletionCount": 5,
                  "currentStreak": 2,
                  "previousBestStreak": 2,
                  "remainingOpportunityCount": 5,
                  "pendingDecisionCount": 0,
                  "groupCompletionRate": 0.8,
                  "previousChallengeCompletionRate": null,
                  "certificationDeadline": "%s",
                  "challengeCompleted": false
                }
                """.formatted(Instant.now().plusSeconds(60 * 60));
    }

    private String pendingRequest() {
        return validRequest()
                .replace("\"todayVerificationPending\": false", "\"todayVerificationPending\": true")
                .replace("\"pendingDecisionCount\": 0", "\"pendingDecisionCount\": 1");
    }
}

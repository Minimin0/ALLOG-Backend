package com.allog.ai.coaching.controller;

import com.allog.ai.coaching.domain.FollowUpQuestion;
import jakarta.validation.constraints.NotNull;

public record AiCoachFollowUpRequest(@NotNull FollowUpQuestion questionId) {
}

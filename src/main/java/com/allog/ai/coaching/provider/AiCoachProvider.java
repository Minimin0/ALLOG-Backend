package com.allog.ai.coaching.provider;

import com.allog.ai.coaching.dto.AiCoachText;
import com.allog.ai.coaching.dto.CoachContext;

public interface AiCoachProvider {

    AiCoachText generate(CoachContext context);

    default boolean isAvailable() {
        return true;
    }
}

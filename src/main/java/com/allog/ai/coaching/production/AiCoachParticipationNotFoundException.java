package com.allog.ai.coaching.production;

public class AiCoachParticipationNotFoundException extends RuntimeException {

    public AiCoachParticipationNotFoundException(Long groupId, Long userId) {
        super("AI Coach participation not found: groupId=" + groupId + ", userId=" + userId);
    }
}

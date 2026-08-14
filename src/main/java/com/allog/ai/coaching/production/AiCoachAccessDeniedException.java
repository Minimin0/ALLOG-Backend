package com.allog.ai.coaching.production;

import com.allog.group.domain.GroupMemberStatus;

public class AiCoachAccessDeniedException extends RuntimeException {

    public AiCoachAccessDeniedException(GroupMemberStatus status) {
        super("AI Coach access denied for participation status: " + status);
    }
}

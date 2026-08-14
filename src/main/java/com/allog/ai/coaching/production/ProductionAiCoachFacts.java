package com.allog.ai.coaching.production;

import com.allog.group.domain.GroupMemberStatus;
import com.allog.progress.domain.GroupProgressFacts;
import com.allog.progress.domain.PersonalProgressFacts;

import java.util.Objects;
import java.util.Optional;

public record ProductionAiCoachFacts(
        String challengeName,
        GroupMemberStatus participationStatus,
        Optional<PersonalProgressFacts> personalProgress,
        Optional<GroupProgressFacts> groupProgress
) {

    public ProductionAiCoachFacts {
        if (challengeName == null || challengeName.isBlank()) {
            throw new IllegalArgumentException("challengeName must not be blank");
        }
        participationStatus = Objects.requireNonNull(
                participationStatus,
                "participationStatus must not be null"
        );
        personalProgress = Objects.requireNonNull(personalProgress, "personalProgress must not be null");
        groupProgress = Objects.requireNonNull(groupProgress, "groupProgress must not be null");
        boolean active = participationStatus == GroupMemberStatus.ACTIVE;
        if (active != (personalProgress.isPresent() && groupProgress.isPresent())) {
            throw new IllegalArgumentException("progress facts must be present only for ACTIVE participation");
        }
    }

    public static ProductionAiCoachFacts lifecycle(String challengeName, GroupMemberStatus status) {
        return new ProductionAiCoachFacts(challengeName, status, Optional.empty(), Optional.empty());
    }

    public static ProductionAiCoachFacts active(
            String challengeName,
            PersonalProgressFacts personalProgress,
            GroupProgressFacts groupProgress
    ) {
        return new ProductionAiCoachFacts(
                challengeName,
                GroupMemberStatus.ACTIVE,
                Optional.of(personalProgress),
                Optional.of(groupProgress)
        );
    }
}

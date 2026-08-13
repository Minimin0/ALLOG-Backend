package com.allog.progress.domain;

import com.allog.group.domain.GroupMemberStatus;

import java.util.Objects;
import java.util.Optional;

public record AuthoritativeProgressFacts(
        String groupName,
        GroupMemberStatus participationStatus,
        Optional<PersonalProgressFacts> personalProgress,
        Optional<GroupProgressFacts> groupProgress
) {

    public AuthoritativeProgressFacts {
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName must not be blank");
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

    public static AuthoritativeProgressFacts lifecycle(String groupName, GroupMemberStatus status) {
        return new AuthoritativeProgressFacts(groupName, status, Optional.empty(), Optional.empty());
    }

    public static AuthoritativeProgressFacts active(
            String groupName,
            PersonalProgressFacts personalProgress,
            GroupProgressFacts groupProgress
    ) {
        return new AuthoritativeProgressFacts(
                groupName,
                GroupMemberStatus.ACTIVE,
                Optional.of(personalProgress),
                Optional.of(groupProgress)
        );
    }
}

package com.allog.progress.dto;

import com.allog.group.domain.GroupMemberStatus;
import com.allog.progress.domain.AuthoritativeProgressFacts;
import com.allog.progress.domain.GroupProgressFacts;
import com.allog.progress.domain.PersonalProgressFacts;

import java.time.Instant;

public record ProgressResponse(
        GroupMemberStatus participationStatus,
        Personal personal,
        Group group
) {

    public static ProgressResponse from(AuthoritativeProgressFacts facts) {
        return new ProgressResponse(
                facts.participationStatus(),
                facts.personalProgress().map(Personal::from).orElse(null),
                facts.groupProgress().map(Group::from).orElse(null)
        );
    }

    public record Personal(
            boolean todayScheduled,
            boolean todayCompleted,
            boolean todayVerificationPending,
            int completedCount,
            int requiredCompletionCount,
            int currentStreak,
            int previousBestStreak,
            int remainingOpportunityCount,
            int pendingDecisionCount,
            Instant certificationDeadline
    ) {

        static Personal from(PersonalProgressFacts facts) {
            return new Personal(
                    facts.todayScheduled(),
                    facts.todayCompleted(),
                    facts.todayVerificationPending(),
                    facts.completedCount(),
                    facts.requiredCompletionCount(),
                    facts.currentStreak(),
                    facts.previousBestStreak(),
                    facts.remainingOpportunityCount(),
                    facts.pendingDecisionCount(),
                    facts.certificationDeadline().orElse(null)
            );
        }
    }

    public record Group(
            int eligibleMemberCount,
            long completedRequirementCount,
            long totalRequiredCount,
            double groupCompletionRate,
            long pendingDecisionCount,
            int goalAchievedMemberCount
    ) {

        static Group from(GroupProgressFacts facts) {
            return new Group(
                    facts.eligibleMemberCount(),
                    facts.completedRequirementCount(),
                    facts.totalRequiredCount(),
                    facts.groupCompletionRate(),
                    facts.pendingDecisionCount(),
                    facts.goalAchievedMemberCount()
            );
        }
    }
}

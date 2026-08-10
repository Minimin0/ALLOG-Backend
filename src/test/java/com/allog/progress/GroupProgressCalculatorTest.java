package com.allog.progress;

import com.allog.group.domain.GroupMemberStatus;
import com.allog.progress.domain.GroupProgressFacts;
import com.allog.progress.domain.PersonalProgressFacts;
import com.allog.progress.service.GroupProgressCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupProgressCalculatorTest {

    private final GroupProgressCalculator calculator = new GroupProgressCalculator();

    @Test
    void calculatesCappedGroupCompletionRate() {
        GroupProgressFacts result = calculator.calculate(List.of(
                facts(5, 5, 0, GroupMemberStatus.ACTIVE),
                facts(3, 5, 0, GroupMemberStatus.ACTIVE)
        ));

        assertAll(
                () -> assertEquals(2, result.eligibleMemberCount()),
                () -> assertEquals(8, result.completedRequirementCount()),
                () -> assertEquals(10, result.totalRequiredCount()),
                () -> assertEquals(0.8, result.groupCompletionRate()),
                () -> assertEquals(1, result.goalAchievedMemberCount())
        );
    }

    @Test
    void capsContributionAtEachMembersRequirement() {
        GroupProgressFacts result = calculator.calculate(List.of(
                facts(7, 5, 0, GroupMemberStatus.ACTIVE),
                facts(5, 5, 0, GroupMemberStatus.ACTIVE)
        ));

        assertAll(
                () -> assertEquals(10, result.completedRequirementCount()),
                () -> assertEquals(1.0, result.groupCompletionRate()),
                () -> assertEquals(2, result.goalAchievedMemberCount())
        );
    }

    @Test
    void sumsGroupPendingDecisions() {
        GroupProgressFacts result = calculator.calculate(List.of(
                facts(3, 5, 1, GroupMemberStatus.ACTIVE),
                facts(4, 5, 2, GroupMemberStatus.ACTIVE)
        ));

        assertEquals(3, result.pendingDecisionCount());
    }

    @Test
    void rejectsEmptyEligibleInput() {
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(List.of()));
    }

    @Test
    void doesNotApplyUnconfirmedMembershipEligibilityPolicy() {
        GroupProgressFacts result = calculator.calculate(List.of(
                facts(5, 5, 0, GroupMemberStatus.LEFT),
                facts(3, 5, 0, GroupMemberStatus.REMOVED)
        ));

        assertAll(
                () -> assertEquals(2, result.eligibleMemberCount()),
                () -> assertEquals(0.8, result.groupCompletionRate())
        );
    }

    private PersonalProgressFacts facts(
            int completedCount,
            int requiredCount,
            int pendingCount,
            GroupMemberStatus status
    ) {
        return new PersonalProgressFacts(
                false,
                false,
                false,
                completedCount,
                requiredCount,
                0,
                0,
                0,
                pendingCount,
                Optional.empty(),
                status
        );
    }
}

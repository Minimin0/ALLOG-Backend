package com.allog.group.domain;

import com.allog.routine.domain.RoutineDefinition;
import com.allog.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The transitions a group and a membership are allowed to make, and the ones they are not. */
class GroupLifecycleTransitionTest {

    private static final Instant ACTIVATION_TIME = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void aFullRoomReopensWhenSomeoneLeavesBeforeTheStart() {
        RoutineGroup group = group(RoutineGroupStatus.FULL);

        group.reopenRecruitingAfterDeparture();

        assertEquals(RoutineGroupStatus.RECRUITING, group.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = RoutineGroupStatus.class, names = "FULL", mode = EnumSource.Mode.EXCLUDE)
    void onlyAFullRoomCanReopen(RoutineGroupStatus status) {
        RoutineGroup group = group(status);

        assertThrows(IllegalStateException.class, group::reopenRecruitingAfterDeparture);
        assertEquals(status, group.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = RoutineGroupStatus.class, names = {"RECRUITING", "FULL"})
    void aGroupCanBeCancelledOrExpiredBeforeItStarts(RoutineGroupStatus status) {
        RoutineGroup cancelled = group(status);
        RoutineGroup expired = group(status);

        cancelled.cancelBeforeStart();
        expired.expireBeforeStart();

        assertEquals(RoutineGroupStatus.CANCELLED, cancelled.getStatus());
        assertEquals(RoutineGroupStatus.EXPIRED, expired.getStatus());
    }

    /** A run that already began cannot be called off, and a finished one cannot be reopened. */
    @ParameterizedTest
    @EnumSource(value = RoutineGroupStatus.class, names = {"RECRUITING", "FULL"}, mode = EnumSource.Mode.EXCLUDE)
    void aStartedOrSettledGroupCannotBeCancelledOrExpired(RoutineGroupStatus status) {
        RoutineGroup group = group(status);

        assertFalse(group.isBeforeStart());
        assertThrows(IllegalStateException.class, group::cancelBeforeStart);
        assertThrows(IllegalStateException.class, group::expireBeforeStart);
        assertEquals(status, group.getStatus());
    }

    @Test
    void onlyARunningGroupCompletes() {
        RoutineGroup running = group(RoutineGroupStatus.ACTIVE);

        running.complete();

        assertEquals(RoutineGroupStatus.COMPLETED, running.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = RoutineGroupStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherGroupStateRefusesCompletion(RoutineGroupStatus status) {
        RoutineGroup group = group(status);

        assertThrows(IllegalStateException.class, group::complete);
        assertEquals(status, group.getStatus());
    }

    /** Terminal is terminal: a settled group never goes back to running or recruiting. */
    @ParameterizedTest
    @EnumSource(value = RoutineGroupStatus.class, names = {"COMPLETED", "CANCELLED", "EXPIRED"})
    void terminalGroupsNeverReverse(RoutineGroupStatus terminal) {
        RoutineGroup group = group(terminal);

        assertThrows(IllegalStateException.class, group::activate);
        assertThrows(IllegalStateException.class, group::markFull);
        assertThrows(IllegalStateException.class, group::reopenRecruitingAfterDeparture);
        assertThrows(IllegalStateException.class, group::cancelBeforeStart);
        assertThrows(IllegalStateException.class, group::complete);
        assertEquals(terminal, group.getStatus());
    }

    @Test
    void aMemberWhoLeavesBeforeTheStartNeverStarted() {
        GroupMember member = member(GroupMemberStatus.JOINED);

        member.leaveBeforeStart();

        assertEquals(GroupMemberStatus.LEFT, member.getStatus());
        assertNull(member.getParticipationStartedAt());
        assertFalse(member.hasStartedParticipation());
    }

    @Test
    void aMemberRemovedWithTheGroupNeverStarted() {
        GroupMember member = member(GroupMemberStatus.JOINED);

        member.removeBeforeStart();

        assertEquals(GroupMemberStatus.REMOVED, member.getStatus());
        assertNull(member.getParticipationStartedAt());
    }

    @Test
    void aRunningMemberCanBeFinalisedEitherWay() {
        GroupMember completed = started();
        GroupMember failed = started();

        completed.completeParticipation();
        failed.failParticipation();

        assertEquals(GroupMemberStatus.COMPLETED, completed.getStatus());
        assertEquals(GroupMemberStatus.FAILED, failed.getStatus());
        assertEquals(ACTIVATION_TIME, completed.getParticipationStartedAt());
        assertTrue(completed.hasStartedParticipation(), "a finalised member still took part");
    }

    @Test
    void aMemberWhoNeverStartedCannotBeFinalised() {
        GroupMember member = member(GroupMemberStatus.JOINED);

        assertThrows(IllegalStateException.class, member::completeParticipation);
        assertThrows(IllegalStateException.class, member::failParticipation);
        assertEquals(GroupMemberStatus.JOINED, member.getStatus());
    }

    @Test
    void aFinalisedMemberIsNotFinalisedAgainAndCannotLeaveAfterwards() {
        GroupMember member = started();
        member.completeParticipation();

        assertThrows(IllegalStateException.class, member::completeParticipation);
        assertThrows(IllegalStateException.class, member::failParticipation);
        assertThrows(IllegalStateException.class, member::leaveBeforeStart);
        assertThrows(IllegalStateException.class, member::removeBeforeStart);
        assertEquals(GroupMemberStatus.COMPLETED, member.getStatus());
    }

    /** Once a run has begun, walking away is not a pre-start departure. */
    @Test
    void aRunningMemberCannotUsePreStartExits() {
        GroupMember member = started();

        assertThrows(IllegalStateException.class, member::leaveBeforeStart);
        assertThrows(IllegalStateException.class, member::removeBeforeStart);
        assertThrows(IllegalStateException.class, () -> member.startParticipation(ACTIVATION_TIME));
        assertEquals(GroupMemberStatus.ACTIVE, member.getStatus());
    }

    private static GroupMember started() {
        GroupMember member = member(GroupMemberStatus.JOINED);
        member.startParticipation(ACTIVATION_TIME);
        return member;
    }

    private static GroupMember member(GroupMemberStatus status) {
        return new GroupMember(
                group(RoutineGroupStatus.RECRUITING),
                User.create(),
                GroupMemberRole.MEMBER,
                status,
                ACTIVATION_TIME.minusSeconds(60));
    }

    private static RoutineGroup group(RoutineGroupStatus status) {
        return new RoutineGroup(
                new RoutineDefinition("물 마시기", null),
                User.create(),
                "아침 물 마시기",
                GroupVisibility.PUBLIC,
                status,
                2,
                1);
    }
}

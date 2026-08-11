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

class MembershipLifecycleDomainTest {

    private static final Instant ACTIVATION_TIME = Instant.parse("2026-08-11T10:00:00Z");

    @Test
    void joinedMemberStartsWithoutOfficialParticipationHistory() {
        GroupMember member = member(GroupMemberStatus.JOINED);

        assertNull(member.getParticipationStartedAt());
    }

    @Test
    void startsJoinedMemberParticipationOnce() {
        GroupMember member = member(GroupMemberStatus.JOINED);

        member.startParticipation(ACTIVATION_TIME);

        assertEquals(GroupMemberStatus.ACTIVE, member.getStatus());
        assertEquals(ACTIVATION_TIME, member.getParticipationStartedAt());
        assertThrows(IllegalStateException.class, () -> member.startParticipation(ACTIVATION_TIME));
    }

    @ParameterizedTest
    @EnumSource(value = GroupMemberStatus.class, names = "JOINED", mode = EnumSource.Mode.EXCLUDE)
    void rejectsParticipationStartFromEveryNonJoinedStatus(GroupMemberStatus status) {
        GroupMember member = member(status);

        assertThrows(IllegalStateException.class, () -> member.startParticipation(ACTIVATION_TIME));
        assertNull(member.getParticipationStartedAt());
    }

    @ParameterizedTest
    @EnumSource(value = RoutineGroupStatus.class, names = {"RECRUITING", "FULL"})
    void activatesFromAllowedGroupStates(RoutineGroupStatus status) {
        RoutineGroup group = group(status);

        group.activate();

        assertEquals(RoutineGroupStatus.ACTIVE, group.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = RoutineGroupStatus.class, names = {"RECRUITING", "FULL"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsActivationFromEveryOtherGroupState(RoutineGroupStatus status) {
        RoutineGroup group = group(status);

        assertFalse(group.canActivate());
        assertThrows(IllegalStateException.class, group::activate);
        assertEquals(status, group.getStatus());
    }

    @ParameterizedTest
    @EnumSource(RoutineGroupStatus.class)
    void acceptsNewMembersOnlyWhileRecruiting(RoutineGroupStatus status) {
        assertEquals(status == RoutineGroupStatus.RECRUITING, group(status).canAcceptNewMember());
    }

    private GroupMember member(GroupMemberStatus status) {
        return new GroupMember(
                group(RoutineGroupStatus.RECRUITING),
                User.create(),
                GroupMemberRole.MEMBER,
                status,
                Instant.parse("2026-08-10T09:00:00Z")
        );
    }

    private RoutineGroup group(RoutineGroupStatus status) {
        User creator = User.create();
        return new RoutineGroup(
                new RoutineDefinition("물 마시기", null),
                creator,
                "건강한 물 마시기",
                GroupVisibility.PUBLIC,
                status,
                10,
                5
        );
    }
}

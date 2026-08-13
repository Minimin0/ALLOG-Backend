package com.allog.progress;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.progress.domain.AuthoritativeProgressFacts;
import com.allog.progress.service.AuthoritativeProgressQueryService;
import com.allog.progress.service.ProgressNotFoundException;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.routine.repository.RoutineScheduleRepository;
import com.allog.user.domain.User;
import com.allog.verification.repository.VerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthoritativeProgressQueryServiceTest {

    private static final Long GROUP_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final Instant SNAPSHOT = Instant.parse("2026-08-11T13:59:59.999999999Z");

    @Mock
    private GroupMemberRepository groupMemberRepository;

    @Mock
    private RoutineScheduleRepository routineScheduleRepository;

    @Mock
    private VerificationRepository verificationRepository;

    @Mock
    private Clock clock;

    private AuthoritativeProgressQueryService service;

    @BeforeEach
    void setUp() {
        service = new AuthoritativeProgressQueryService(
                groupMemberRepository,
                routineScheduleRepository,
                verificationRepository,
                clock
        );
    }

    @Test
    void capturesOneSnapshotForEveryOfficialParticipant() {
        Fixture fixture = activeFixture(3);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(fixture.members().getFirst()));
        when(clock.instant()).thenReturn(SNAPSHOT, SNAPSHOT.plusNanos(1));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(routineScheduleRepository.findByRoutineGroup_Id(GROUP_ID))
                .thenReturn(Optional.of(fixture.schedule()));
        when(groupMemberRepository.findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(GROUP_ID))
                .thenReturn(fixture.members());
        when(verificationRepository.findAllForProgressBatch(
                fixture.schedule().getId(),
                Set.of(10L, 11L, 12L)
        )).thenReturn(List.of());

        AuthoritativeProgressFacts result = service.load(GROUP_ID, USER_ID);

        assertEquals(3, result.groupProgress().orElseThrow().eligibleMemberCount());
        assertEquals(3, result.personalProgress().orElseThrow().remainingOpportunityCount());
        verify(clock, times(1)).instant();
    }

    @ParameterizedTest
    @EnumSource(value = GroupMemberStatus.class, names = {"JOINED", "COMPLETED", "FAILED", "LEFT", "REMOVED"})
    void lifecycleStatusShortCircuitsActiveQueries(GroupMemberStatus status) {
        Fixture fixture = lifecycleFixture(status);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(fixture.members().getFirst()));

        AuthoritativeProgressFacts result = service.load(GROUP_ID, USER_ID);

        assertEquals(status, result.participationStatus());
        assertEquals(Optional.empty(), result.personalProgress());
        verify(clock, never()).instant();
        verify(routineScheduleRepository, never()).findByRoutineGroup_Id(any());
        verify(groupMemberRepository, never())
                .findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(any());
        verify(verificationRepository, never()).findAllForProgressBatch(any(), any());
    }

    @Test
    void rejectsMissingMembershipWithoutActiveQueries() {
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThrows(ProgressNotFoundException.class, () -> service.load(GROUP_ID, USER_ID));

        verify(clock, never()).instant();
        verify(routineScheduleRepository, never()).findByRoutineGroup_Id(any());
    }

    @Test
    void rejectsActiveMemberWithoutParticipationStart() {
        Fixture fixture = lifecycleFixture(GroupMemberStatus.ACTIVE);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(fixture.members().getFirst()));

        assertThrows(IllegalStateException.class, () -> service.load(GROUP_ID, USER_ID));

        verify(clock, never()).instant();
        verify(routineScheduleRepository, never()).findByRoutineGroup_Id(any());
    }

    @Test
    void rejectsJoinedMemberWithParticipationStart() {
        Fixture fixture = activeFixture(1);
        GroupMember member = fixture.members().getFirst();
        ReflectionTestUtils.setField(member, "status", GroupMemberStatus.JOINED);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(member));

        assertThrows(IllegalStateException.class, () -> service.load(GROUP_ID, USER_ID));

        verify(clock, never()).instant();
        verify(routineScheduleRepository, never()).findByRoutineGroup_Id(any());
    }

    @Test
    void rejectsMissingScheduleAfterOneSnapshot() {
        Fixture fixture = activeFixture(1);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(fixture.members().getFirst()));
        when(clock.instant()).thenReturn(SNAPSHOT);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(routineScheduleRepository.findByRoutineGroup_Id(GROUP_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.load(GROUP_ID, USER_ID));

        verify(clock, times(1)).instant();
        verify(groupMemberRepository, never())
                .findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(any());
    }

    @Test
    void rejectsDuplicateOfficialParticipantBeforeVerificationQuery() {
        Fixture fixture = activeFixture(1);
        GroupMember member = fixture.members().getFirst();
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(member));
        when(clock.instant()).thenReturn(SNAPSHOT);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(routineScheduleRepository.findByRoutineGroup_Id(GROUP_ID))
                .thenReturn(Optional.of(fixture.schedule()));
        when(groupMemberRepository.findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(GROUP_ID))
                .thenReturn(List.of(member, member));

        assertThrows(IllegalStateException.class, () -> service.load(GROUP_ID, USER_ID));

        verify(verificationRepository, never()).findAllForProgressBatch(any(), any());
    }

    @Test
    void rejectsActiveTargetMissingFromOfficialParticipants() {
        Fixture fixture = activeFixture(1);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(fixture.members().getFirst()));
        when(clock.instant()).thenReturn(SNAPSHOT);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(routineScheduleRepository.findByRoutineGroup_Id(GROUP_ID))
                .thenReturn(Optional.of(fixture.schedule()));
        when(groupMemberRepository.findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(GROUP_ID))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> service.load(GROUP_ID, USER_ID));

        verify(verificationRepository, never()).findAllForProgressBatch(any(), any());
    }

    private Fixture activeFixture(int memberCount) {
        Fixture fixture = fixture(GroupMemberStatus.JOINED, memberCount);
        fixture.members().forEach(member -> member.startParticipation(Instant.parse("2026-08-07T00:00:00Z")));
        return fixture;
    }

    private Fixture lifecycleFixture(GroupMemberStatus status) {
        return fixture(status, 1);
    }

    private Fixture fixture(GroupMemberStatus status, int memberCount) {
        User creator = User.create();
        RoutineGroup group = new RoutineGroup(
                new RoutineDefinition("물 마시기", null),
                creator,
                "아침 물 마시기",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.ACTIVE,
                10,
                3
        );
        ReflectionTestUtils.setField(group, "id", GROUP_ID);
        RoutineSchedule schedule = new RoutineSchedule(
                group,
                ScheduleType.DAILY,
                LocalDate.of(2026, 8, 7),
                LocalDate.of(2026, 8, 13),
                LocalTime.of(23, 0),
                "Asia/Seoul",
                Set.of()
        );
        ReflectionTestUtils.setField(schedule, "id", 20L);

        java.util.ArrayList<GroupMember> members = new java.util.ArrayList<>();
        for (int index = 0; index < memberCount; index++) {
            GroupMember member = new GroupMember(
                    group,
                    index == 0 ? creator : User.create(),
                    index == 0 ? GroupMemberRole.OWNER : GroupMemberRole.MEMBER,
                    status,
                    Instant.parse("2026-08-01T09:00:00Z").plusSeconds(index)
            );
            ReflectionTestUtils.setField(member, "id", 10L + index);
            members.add(member);
        }
        return new Fixture(schedule, List.copyOf(members));
    }

    private record Fixture(RoutineSchedule schedule, List<GroupMember> members) {
    }
}

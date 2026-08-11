package com.allog.ai.coaching.production;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.routine.repository.RoutineScheduleRepository;
import com.allog.user.domain.User;
import com.allog.verification.domain.Verification;
import com.allog.verification.repository.VerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionAiCoachQueryServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC);
    private static final Long GROUP_ID = 1L;
    private static final Long USER_ID = 2L;

    @Mock
    private GroupMemberRepository groupMemberRepository;

    @Mock
    private RoutineScheduleRepository routineScheduleRepository;

    @Mock
    private VerificationRepository verificationRepository;

    private ProductionAiCoachQueryService service;

    @BeforeEach
    void setUp() {
        service = new ProductionAiCoachQueryService(
                groupMemberRepository,
                routineScheduleRepository,
                verificationRepository,
                CLOCK
        );
    }

    @Test
    void calculatesThreeMembersWithOneVerificationBatchQuery() {
        Fixture fixture = fixture(3);
        List<Verification> verifications = new ArrayList<>();
        verifications.addAll(approved(fixture.members().get(0), fixture.schedule(), 5));
        verifications.addAll(approved(fixture.members().get(1), fixture.schedule(), 5));
        verifications.addAll(approved(fixture.members().get(2), fixture.schedule(), 2));
        stubActive(fixture, verifications);

        ProductionAiCoachFacts result = service.load(GROUP_ID, USER_ID);

        assertEquals(5, result.personalProgress().orElseThrow().completedCount());
        assertEquals(3, result.groupProgress().orElseThrow().eligibleMemberCount());
        assertEquals(0.8, result.groupProgress().orElseThrow().groupCompletionRate());
        verify(verificationRepository).findAllForProgressBatch(
                fixture.schedule().getId(),
                Set.of(10L, 11L, 12L)
        );
        verify(verificationRepository, never())
                .findAllByGroupMemberAndRoutineScheduleOrderByScheduledDateAsc(any(), any());
    }

    @Test
    void rejectsDuplicateEligibleMemberBeforeVerificationQuery() {
        Fixture fixture = fixture(1);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(fixture.members().getFirst()));
        when(routineScheduleRepository.findByRoutineGroup_Id(GROUP_ID))
                .thenReturn(Optional.of(fixture.schedule()));
        when(groupMemberRepository.findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(GROUP_ID))
                .thenReturn(List.of(fixture.members().getFirst(), fixture.members().getFirst()));

        assertThrows(IllegalStateException.class, () -> service.load(GROUP_ID, USER_ID));

        verify(verificationRepository, never()).findAllForProgressBatch(any(), any());
    }

    @Test
    void rejectsActiveMemberWithoutParticipationHistory() {
        Fixture fixture = fixture(0);
        GroupMember inconsistent = new GroupMember(
                fixture.group(),
                User.create(),
                GroupMemberRole.MEMBER,
                GroupMemberStatus.ACTIVE,
                Instant.parse("2026-08-01T09:00:00Z")
        );
        ReflectionTestUtils.setField(inconsistent, "id", 10L);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(inconsistent));

        assertThrows(IllegalStateException.class, () -> service.load(GROUP_ID, USER_ID));

        verify(routineScheduleRepository, never()).findByRoutineGroup_Id(any());
    }

    @Test
    void rejectsJoinedMemberWithParticipationHistory() {
        Fixture fixture = fixture(1);
        GroupMember inconsistent = fixture.members().getFirst();
        ReflectionTestUtils.setField(inconsistent, "status", GroupMemberStatus.JOINED);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(inconsistent));

        assertThrows(IllegalStateException.class, () -> service.load(GROUP_ID, USER_ID));

        verify(routineScheduleRepository, never()).findByRoutineGroup_Id(any());
    }

    @Test
    void rejectsMissingScheduleForActiveMember() {
        Fixture fixture = fixture(1);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(fixture.members().getFirst()));
        when(routineScheduleRepository.findByRoutineGroup_Id(GROUP_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.load(GROUP_ID, USER_ID));
    }

    @Test
    void rejectsActiveTargetMissingFromOfficialParticipants() {
        Fixture fixture = fixture(1);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(fixture.members().getFirst()));
        when(routineScheduleRepository.findByRoutineGroup_Id(GROUP_ID))
                .thenReturn(Optional.of(fixture.schedule()));
        when(groupMemberRepository.findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(GROUP_ID))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> service.load(GROUP_ID, USER_ID));

        verify(verificationRepository, never()).findAllForProgressBatch(any(), any());
    }

    @Test
    void rejectsMissingParticipationWithoutFurtherQueries() {
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThrows(
                AiCoachParticipationNotFoundException.class,
                () -> service.load(GROUP_ID, USER_ID)
        );

        verify(routineScheduleRepository, never()).findByRoutineGroup_Id(any());
        verify(verificationRepository, never()).findAllForProgressBatch(any(), any());
    }

    private void stubActive(Fixture fixture, List<Verification> verifications) {
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_Id(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(fixture.members().getFirst()));
        when(routineScheduleRepository.findByRoutineGroup_Id(GROUP_ID))
                .thenReturn(Optional.of(fixture.schedule()));
        when(groupMemberRepository.findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(GROUP_ID))
                .thenReturn(fixture.members());
        when(verificationRepository.findAllForProgressBatch(
                fixture.schedule().getId(),
                Set.of(10L, 11L, 12L)
        )).thenReturn(verifications);
    }

    private List<Verification> approved(GroupMember member, RoutineSchedule schedule, int count) {
        List<Verification> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Verification verification = Verification.create(
                    member,
                    schedule,
                    LocalDate.of(2026, 8, 7).plusDays(index)
            );
            verification.submit(CLOCK);
            verification.startProcessing();
            verification.approve(CLOCK);
            result.add(verification);
        }
        return result;
    }

    private Fixture fixture(int memberCount) {
        User creator = User.create();
        RoutineGroup group = new RoutineGroup(
                new RoutineDefinition("물 마시기", null),
                creator,
                "아침 물 마시기",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.ACTIVE,
                10,
                5
        );
        ReflectionTestUtils.setField(group, "id", GROUP_ID);
        RoutineSchedule schedule = new RoutineSchedule(
                group,
                ScheduleType.DAILY,
                LocalDate.of(2026, 8, 7),
                LocalDate.of(2026, 8, 11),
                LocalTime.of(23, 0),
                "Asia/Seoul",
                Set.of()
        );
        ReflectionTestUtils.setField(schedule, "id", 20L);

        List<GroupMember> members = new ArrayList<>();
        for (int index = 0; index < memberCount; index++) {
            GroupMember member = new GroupMember(
                    group,
                    index == 0 ? creator : User.create(),
                    index == 0 ? GroupMemberRole.OWNER : GroupMemberRole.MEMBER,
                    GroupMemberStatus.JOINED,
                    Instant.parse("2026-08-01T09:00:00Z").plusSeconds(60L * index)
            );
            member.startParticipation(Instant.parse("2026-08-07T00:00:00Z"));
            ReflectionTestUtils.setField(member, "id", 10L + index);
            members.add(member);
        }
        return new Fixture(group, schedule, List.copyOf(members));
    }

    private record Fixture(
            RoutineGroup group,
            RoutineSchedule schedule,
            List<GroupMember> members
    ) {
    }
}

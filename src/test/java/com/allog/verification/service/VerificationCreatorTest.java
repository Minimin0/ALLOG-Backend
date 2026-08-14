package com.allog.verification.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.repository.VerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationCreatorTest {

    private static final LocalDate SCHEDULED_DATE = LocalDate.of(2026, 8, 11);

    @Mock
    private VerificationRepository repository;

    private VerificationCreator creator;

    @BeforeEach
    void setUp() {
        creator = new VerificationCreator(repository);
    }

    @Test
    void createsPendingVerificationForValidOpportunity() {
        Fixture fixture = fixture();
        when(repository.save(any(Verification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Verification result = creator.create(fixture.member(), fixture.schedule(), SCHEDULED_DATE);

        assertEquals(VerificationStatus.PENDING_UPLOAD, result.getStatus());
        verify(repository).save(result);
    }

    @Test
    void rejectsScheduleFromDifferentGroup() {
        Fixture fixture = fixture();
        RoutineSchedule otherSchedule = schedule(group(User.create()));

        assertThrows(
                IllegalArgumentException.class,
                () -> creator.create(fixture.member(), otherSchedule, SCHEDULED_DATE)
        );
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsDateThatIsNotScheduled() {
        Fixture fixture = fixture();

        assertThrows(
                IllegalArgumentException.class,
                () -> creator.create(fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 17))
        );
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsExistingAggregateBeforeInsert() {
        Fixture fixture = fixture();
        when(repository.existsByGroupMemberAndRoutineScheduleAndScheduledDate(
                fixture.member(), fixture.schedule(), SCHEDULED_DATE
        )).thenReturn(true);

        assertThrows(
                IllegalStateException.class,
                () -> creator.create(fixture.member(), fixture.schedule(), SCHEDULED_DATE)
        );
        verify(repository, never()).save(any());
    }

    private Fixture fixture() {
        User user = User.create();
        RoutineGroup group = group(user);
        RoutineSchedule schedule = schedule(group);
        GroupMember member = new GroupMember(
                group,
                user,
                GroupMemberRole.OWNER,
                GroupMemberStatus.ACTIVE,
                Instant.parse("2026-08-01T09:00:00Z")
        );
        return new Fixture(member, schedule);
    }

    private RoutineGroup group(User user) {
        return new RoutineGroup(
                new RoutineDefinition("물 마시기", null),
                user,
                "건강한 물 마시기",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.ACTIVE,
                5,
                3
        );
    }

    private RoutineSchedule schedule(RoutineGroup group) {
        return new RoutineSchedule(
                group,
                ScheduleType.DAILY,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 16),
                LocalTime.of(23, 0),
                "Asia/Seoul",
                Set.of()
        );
    }

    private record Fixture(GroupMember member, RoutineSchedule schedule) {
    }
}

package com.allog.verification.service;

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
import com.allog.verification.domain.VerificationMedia;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.repository.VerificationMediaRepository;
import com.allog.verification.repository.VerificationRepository;
import com.allog.verification.storage.VerificationMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificationCommandServiceTest {

    private static final Long GROUP_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final Instant BEFORE_DEADLINE = Instant.parse("2026-08-11T13:59:59.999999999Z");
    private static final Instant DEADLINE = Instant.parse("2026-08-11T14:00:00Z");
    private static final VerificationMediaStorage.StoredMediaInspection INSPECTION =
            new VerificationMediaStorage.StoredMediaInspection("verification-media/test", 10, "video/mp4");

    @Mock
    private GroupMemberRepository groupMemberRepository;
    @Mock
    private RoutineScheduleRepository routineScheduleRepository;
    @Mock
    private VerificationRepository verificationRepository;
    @Mock
    private VerificationMediaRepository verificationMediaRepository;
    @Mock
    private VerificationCreator verificationCreator;
    @Mock
    private VerificationMediaPolicy mediaPolicy;

    private CountingClock clock;

    @BeforeEach
    void setUp() {
        clock = new CountingClock(BEFORE_DEADLINE);
    }

    @Test
    void createsDailyCurrentSlotUsingOneClockSnapshot() {
        Fixture fixture = activeFixture(ScheduleType.DAILY, Set.of(), "Asia/Seoul", BEFORE_DEADLINE);
        Verification created = Verification.create(fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11));
        stubActive(fixture);
        when(verificationCreator.create(fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)))
                .thenReturn(created);

        Verification result = service().createOrGetCurrent(GROUP_ID, USER_ID);

        assertAll(
                () -> assertSame(created, result),
                () -> assertEquals(VerificationStatus.PENDING_UPLOAD, result.getStatus()),
                () -> assertEquals(1, clock.instantCalls())
        );
        verify(verificationRepository).findByGroupMember_IdAndRoutineSchedule_IdAndScheduledDate(
                null, null, LocalDate.of(2026, 8, 11)
        );
    }

    @Test
    void scheduleTimezoneOwnsCurrentDateWhenUtcDateDiffers() {
        clock = new CountingClock(Instant.parse("2026-08-10T15:30:00Z"));
        Fixture fixture = activeFixture(
                ScheduleType.SPECIFIC_DAYS,
                Set.of(DayOfWeek.TUESDAY),
                "Asia/Seoul",
                Instant.parse("2026-08-01T00:00:00Z")
        );
        Verification created = Verification.create(fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11));
        stubActive(fixture);
        when(verificationCreator.create(fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)))
                .thenReturn(created);

        service().createOrGetCurrent(GROUP_ID, USER_ID);

        verify(verificationCreator).create(fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11));
    }

    @Test
    void createsSpecificDaysSlotOnScheduledCurrentDay() {
        Fixture fixture = activeFixture(
                ScheduleType.SPECIFIC_DAYS,
                Set.of(DayOfWeek.TUESDAY),
                "Asia/Seoul",
                BEFORE_DEADLINE
        );
        stubActive(fixture);
        when(verificationCreator.create(any(), any(), any())).thenAnswer(invocation -> Verification.create(
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)
        ));

        assertEquals(
                LocalDate.of(2026, 8, 11),
                service().createOrGetCurrent(GROUP_ID, USER_ID).getScheduledDate()
        );
    }

    @Test
    void rejectsSpecificDaysSlotOnNonScheduledCurrentDay() {
        Fixture fixture = activeFixture(
                ScheduleType.SPECIFIC_DAYS,
                Set.of(DayOfWeek.MONDAY),
                "Asia/Seoul",
                BEFORE_DEADLINE
        );
        stubActive(fixture);

        assertThrows(
                VerificationCommandConflictException.class,
                () -> service().createOrGetCurrent(GROUP_ID, USER_ID)
        );
        verify(verificationCreator, never()).create(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-08-11T14:00:00Z", "2026-08-11T14:00:00.000000001Z"})
    void deadlineAndAfterCloseNewCreateButExistingSlotRemainsReadable(String snapshot) {
        clock = new CountingClock(Instant.parse(snapshot));
        Fixture fixture = activeFixture(ScheduleType.DAILY, Set.of(), "Asia/Seoul", BEFORE_DEADLINE);
        stubActive(fixture);

        assertThrows(
                VerificationCommandConflictException.class,
                () -> service().createOrGetCurrent(GROUP_ID, USER_ID)
        );

        Verification existing = Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)
        );
        when(verificationRepository.findByGroupMember_IdAndRoutineSchedule_IdAndScheduledDate(
                null, null, LocalDate.of(2026, 8, 11)
        )).thenReturn(Optional.of(existing));

        assertSame(existing, service().createOrGetCurrent(GROUP_ID, USER_ID));
    }

    @Test
    void submitsOneNanosecondBeforeDeadlineUsingOneClockSnapshot() {
        Fixture fixture = activeFixture(ScheduleType.DAILY, Set.of(), "Asia/Seoul", BEFORE_DEADLINE);
        Verification pending = Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)
        );
        stubSubmit(fixture, pending);

        Verification result = service().submitInspectedCurrent(GROUP_ID, USER_ID, INSPECTION);

        assertAll(
                () -> assertEquals(VerificationStatus.SUBMITTED, result.getStatus()),
                () -> assertEquals(BEFORE_DEADLINE, result.getSubmittedAt()),
                () -> assertEquals(1, clock.instantCalls())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-08-11T14:00:00Z", "2026-08-11T14:00:00.000000001Z"})
    void deadlineAndAfterCloseInitialSubmit(String snapshot) {
        clock = new CountingClock(Instant.parse(snapshot));
        Fixture fixture = activeFixture(ScheduleType.DAILY, Set.of(), "Asia/Seoul", BEFORE_DEADLINE);
        Verification pending = Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)
        );
        stubSubmit(fixture, pending);

        assertThrows(
                VerificationCommandConflictException.class,
                () -> service().submitInspectedCurrent(GROUP_ID, USER_ID, INSPECTION)
        );
        assertEquals(VerificationStatus.PENDING_UPLOAD, pending.getStatus());
    }

    @Test
    void submitBeforeParticipationStartFails() {
        clock = new CountingClock(Instant.parse("2026-08-11T10:00:00Z"));
        Fixture fixture = activeFixture(
                ScheduleType.DAILY,
                Set.of(),
                "Asia/Seoul",
                Instant.parse("2026-08-11T10:00:01Z")
        );
        Verification pending = Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)
        );
        stubSubmit(fixture, pending);

        assertThrows(
                VerificationCommandConflictException.class,
                () -> service().submitInspectedCurrent(GROUP_ID, USER_ID, INSPECTION)
        );
    }

    @Test
    void opportunityAtParticipationBoundaryIsIneligible() {
        Fixture fixture = activeFixture(ScheduleType.DAILY, Set.of(), "Asia/Seoul", DEADLINE);
        stubActive(fixture);

        assertThrows(
                VerificationCommandConflictException.class,
                () -> service().createOrGetCurrent(GROUP_ID, USER_ID)
        );
    }

    @ParameterizedTest
    @EnumSource(value = VerificationStatus.class, names = {
            "SUBMITTED", "PROCESSING", "REVIEW_REQUIRED", "APPROVED", "REJECTED", "INVALIDATED"
    })
    void repeatedSubmitIsIdempotentAndPreservesSubmittedAt(VerificationStatus status) {
        Fixture fixture = activeFixture(ScheduleType.DAILY, Set.of(), "Asia/Seoul", BEFORE_DEADLINE);
        Verification verification = verification(fixture, status);
        Instant firstSubmittedAt = verification.getSubmittedAt();
        stubSubmit(fixture, verification);

        Verification result = service().submitInspectedCurrent(GROUP_ID, USER_ID, INSPECTION);

        assertAll(
                () -> assertSame(verification, result),
                () -> assertEquals(status, result.getStatus()),
                () -> assertEquals(firstSubmittedAt, result.getSubmittedAt())
        );
    }

    @Test
    void userRetryIsNotEnabled() {
        Fixture fixture = activeFixture(ScheduleType.DAILY, Set.of(), "Asia/Seoul", BEFORE_DEADLINE);
        Verification retry = verification(fixture, VerificationStatus.RETRY_REQUIRED);
        stubSubmit(fixture, retry);

        assertThrows(
                VerificationCommandConflictException.class,
                () -> service().submitInspectedCurrent(GROUP_ID, USER_ID, INSPECTION)
        );
    }

    @ParameterizedTest
    @EnumSource(value = GroupMemberStatus.class, names = {"JOINED", "COMPLETED", "FAILED"})
    void visibleInactiveMembershipIsConflict(GroupMemberStatus status) {
        GroupMember member = member(group(RoutineGroupStatus.ACTIVE), status);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_IdForUpdate(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(member));

        assertThrows(
                VerificationCommandConflictException.class,
                () -> service().createOrGetCurrent(GROUP_ID, USER_ID)
        );
    }

    @ParameterizedTest
    @EnumSource(value = GroupMemberStatus.class, names = {"LEFT", "REMOVED"})
    void hiddenMembershipIsNotFound(GroupMemberStatus status) {
        GroupMember member = member(group(RoutineGroupStatus.ACTIVE), status);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_IdForUpdate(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(member));

        assertThrows(
                VerificationMembershipNotFoundException.class,
                () -> service().createOrGetCurrent(GROUP_ID, USER_ID)
        );
    }

    @Test
    void noMembershipIsNotFound() {
        assertThrows(
                VerificationMembershipNotFoundException.class,
                () -> service().createOrGetCurrent(GROUP_ID, USER_ID)
        );
    }

    @Test
    void activeMemberInNonActiveGroupIsInvariantFailure() {
        GroupMember member = activeMember(group(RoutineGroupStatus.COMPLETED), BEFORE_DEADLINE);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_IdForUpdate(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(member));

        assertThrows(IllegalStateException.class, () -> service().createOrGetCurrent(GROUP_ID, USER_ID));
    }

    @Test
    void activeMemberWithoutParticipationStartIsInvariantFailure() {
        GroupMember member = member(group(RoutineGroupStatus.ACTIVE), GroupMemberStatus.ACTIVE);
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_IdForUpdate(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(member));

        assertThrows(IllegalStateException.class, () -> service().createOrGetCurrent(GROUP_ID, USER_ID));
    }

    @Test
    void submitUsesMembershipThenVerificationLockOrder() {
        Fixture fixture = activeFixture(ScheduleType.DAILY, Set.of(), "Asia/Seoul", BEFORE_DEADLINE);
        Verification pending = Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)
        );
        stubSubmit(fixture, pending);

        service().submitInspectedCurrent(GROUP_ID, USER_ID, INSPECTION);

        var order = org.mockito.Mockito.inOrder(
                groupMemberRepository,
                verificationRepository,
                verificationMediaRepository
        );
        order.verify(groupMemberRepository).findByRoutineGroup_IdAndUser_IdForUpdate(GROUP_ID, USER_ID);
        order.verify(verificationRepository).findCurrentForUpdate(null, null, LocalDate.of(2026, 8, 11));
        order.verify(verificationMediaRepository).findByVerificationIdForUpdate(null);
    }

    private VerificationCommandService service() {
        return new VerificationCommandService(
                groupMemberRepository,
                routineScheduleRepository,
                verificationRepository,
                verificationMediaRepository,
                verificationCreator,
                mediaPolicy,
                clock
        );
    }

    private void stubActive(Fixture fixture) {
        when(groupMemberRepository.findByRoutineGroup_IdAndUser_IdForUpdate(GROUP_ID, USER_ID))
                .thenReturn(Optional.of(fixture.member()));
        when(routineScheduleRepository.findByRoutineGroup_Id(GROUP_ID))
                .thenReturn(Optional.of(fixture.schedule()));
    }

    private void stubSubmit(Fixture fixture, Verification verification) {
        stubActive(fixture);
        when(verificationRepository.findCurrentForUpdate(null, null, LocalDate.of(2026, 8, 11)))
                .thenReturn(Optional.of(verification));
        VerificationMedia media = VerificationMedia.create(
                verification,
                INSPECTION.objectKey(),
                INSPECTION.contentType(),
                INSPECTION.contentLength()
        );
        if (verification.getStatus() != VerificationStatus.PENDING_UPLOAD) {
            media.confirm(INSPECTION.contentLength(), Clock.fixed(BEFORE_DEADLINE, ZoneOffset.UTC));
        }
        when(verificationMediaRepository.findByVerificationIdForUpdate(verification.getId()))
                .thenReturn(Optional.of(media));
    }

    private Verification verification(Fixture fixture, VerificationStatus status) {
        Verification verification = Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)
        );
        Clock first = Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC);
        Clock second = Clock.fixed(Instant.parse("2026-08-11T11:00:00Z"), ZoneOffset.UTC);
        verification.submit(first);
        if (status == VerificationStatus.SUBMITTED) {
            return verification;
        }
        verification.startProcessing();
        switch (status) {
            case PROCESSING -> {
            }
            case REVIEW_REQUIRED -> verification.requestReview();
            case APPROVED -> verification.approve(second);
            case RETRY_REQUIRED -> verification.requestRetry();
            case REJECTED -> verification.reject();
            case INVALIDATED -> {
                verification.approve(second);
                verification.invalidate(second);
            }
            default -> throw new IllegalArgumentException("unsupported fixture status: " + status);
        }
        return verification;
    }

    private Fixture activeFixture(
            ScheduleType type,
            Set<DayOfWeek> days,
            String timezone,
            Instant participationStartedAt
    ) {
        RoutineGroup group = group(RoutineGroupStatus.ACTIVE);
        GroupMember member = activeMember(group, participationStartedAt);
        RoutineSchedule schedule = new RoutineSchedule(
                group,
                type,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 16),
                LocalTime.of(23, 0),
                timezone,
                days
        );
        return new Fixture(member, schedule);
    }

    private GroupMember activeMember(RoutineGroup group, Instant participationStartedAt) {
        GroupMember member = member(group, GroupMemberStatus.JOINED);
        member.startParticipation(participationStartedAt);
        return member;
    }

    private GroupMember member(RoutineGroup group, GroupMemberStatus status) {
        return new GroupMember(
                group,
                group.getCreatedBy(),
                GroupMemberRole.OWNER,
                status,
                Instant.parse("2026-08-01T00:00:00Z")
        );
    }

    private RoutineGroup group(RoutineGroupStatus status) {
        User user = User.create();
        return new RoutineGroup(
                new RoutineDefinition("water", null),
                user,
                "water group",
                GroupVisibility.PUBLIC,
                status,
                5,
                1
        );
    }

    private record Fixture(GroupMember member, RoutineSchedule schedule) {
    }

    private static final class CountingClock extends Clock {

        private final Instant instant;
        private int calls;

        private CountingClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            calls++;
            return instant;
        }

        private int instantCalls() {
            return calls;
        }
    }
}

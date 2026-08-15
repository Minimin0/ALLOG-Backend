package com.allog.progress;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.progress.domain.PersonalProgressFacts;
import com.allog.progress.service.PersonalProgressCalculator;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalProgressCalculatorTest {

    private static final Clock TRANSITION_CLOCK = clock("2026-08-01T00:00:00Z");

    private final PersonalProgressCalculator calculator = new PersonalProgressCalculator();

    @Test
    void todayApprovedIsCompleted() {
        Fixture fixture = daily("2026-08-11", "2026-08-13", 3);
        PersonalProgressFacts facts = calculate(
                fixture,
                List.of(verification(fixture, "2026-08-11", VerificationStatus.APPROVED)),
                "2026-08-11T10:00:00Z"
        );

        assertAll(
                () -> assertTrue(facts.todayScheduled()),
                () -> assertTrue(facts.todayCompleted()),
                () -> assertFalse(facts.todayVerificationPending()),
                () -> assertEquals(1, facts.completedCount()),
                () -> assertEquals(Instant.parse("2026-08-11T14:00:00Z"),
                        facts.certificationDeadline().orElseThrow()),
                () -> assertEquals(GroupMemberStatus.ACTIVE, facts.participationStatus())
        );
    }

    @ParameterizedTest
    @EnumSource(value = VerificationStatus.class, names = {"SUBMITTED", "PROCESSING", "REVIEW_REQUIRED"})
    void todayDecisionPendingIsNotCompletedOrActionable(VerificationStatus status) {
        Fixture fixture = daily("2026-08-11", "2026-08-13", 3);
        PersonalProgressFacts facts = calculate(
                fixture,
                List.of(verification(fixture, "2026-08-11", status)),
                "2026-08-11T10:00:00Z"
        );

        assertAll(
                () -> assertFalse(facts.todayCompleted()),
                () -> assertTrue(facts.todayVerificationPending()),
                () -> assertEquals(1, facts.pendingDecisionCount()),
                () -> assertEquals(2, facts.remainingOpportunityCount())
        );
    }

    @Test
    void unscheduledTodayIsNotCompletedAndHasNoDeadline() {
        Fixture fixture = specific(
                "2026-08-10",
                "2026-08-14",
                3,
                DayOfWeek.MONDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY
        );
        PersonalProgressFacts facts = calculate(fixture, List.of(), "2026-08-11T10:00:00Z");

        assertAll(
                () -> assertFalse(facts.todayScheduled()),
                () -> assertFalse(facts.todayCompleted()),
                () -> assertFalse(facts.todayVerificationPending()),
                () -> assertTrue(facts.certificationDeadline().isEmpty())
        );
    }

    @Test
    void completedCountUsesOnlyApprovedScheduledOpportunities() {
        Fixture fixture = daily("2026-08-10", "2026-08-12", 3);
        PersonalProgressFacts facts = calculate(
                fixture,
                List.of(
                        verification(fixture, "2026-08-10", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-11", VerificationStatus.INVALIDATED),
                        verification(fixture, "2026-08-12", VerificationStatus.APPROVED)
                ),
                "2026-08-12T10:00:00Z"
        );

        assertEquals(2, facts.completedCount());
    }

    @Test
    void remainingIncludesOpenTodayAndTwoFutureOpportunities() {
        Fixture fixture = daily("2026-08-11", "2026-08-13", 3);

        assertEquals(3, calculate(fixture, List.of(), "2026-08-11T10:00:00Z")
                .remainingOpportunityCount());
    }

    @Test
    void remainingExcludesApprovedToday() {
        Fixture fixture = daily("2026-08-11", "2026-08-13", 3);

        assertEquals(2, calculate(
                fixture,
                List.of(verification(fixture, "2026-08-11", VerificationStatus.APPROVED)),
                "2026-08-11T10:00:00Z"
        ).remainingOpportunityCount());
    }

    @Test
    void remainingExcludesUnapprovedTodayAfterDeadline() {
        Fixture fixture = daily("2026-08-11", "2026-08-13", 3);

        assertEquals(2, calculate(fixture, List.of(), "2026-08-11T14:30:00Z")
                .remainingOpportunityCount());
    }

    @Test
    void midScheduleParticipationUsesOnlyEligibleOpportunitiesForAllProgressFacts() {
        Fixture fixture = dailyStartedAt(
                "2026-08-01",
                "2026-08-08",
                3,
                "2026-08-05T00:00:00Z"
        );
        PersonalProgressFacts facts = calculate(
                fixture,
                List.of(
                        verification(fixture, "2026-08-05", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-07", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-08", VerificationStatus.APPROVED)
                ),
                "2026-08-08T10:00:00Z"
        );

        assertAll(
                () -> assertEquals(3, facts.completedCount()),
                () -> assertEquals(0, facts.remainingOpportunityCount()),
                () -> assertEquals(2, facts.currentStreak()),
                () -> assertEquals(1, facts.previousBestStreak())
        );
    }

    @Test
    void sameDayParticipationBeforeDeadlineIncludesToday() {
        Fixture fixture = dailyStartedAt(
                "2026-08-05", "2026-08-06", 2, "2026-08-05T13:00:00Z"
        );

        PersonalProgressFacts facts = calculate(fixture, List.of(), "2026-08-05T13:30:00Z");

        assertAll(
                () -> assertTrue(facts.todayScheduled()),
                () -> assertEquals(2, facts.remainingOpportunityCount()),
                () -> assertEquals(
                        Instant.parse("2026-08-05T14:00:00Z"),
                        facts.certificationDeadline().orElseThrow()
                )
        );
    }

    @Test
    void sameDayParticipationAfterDeadlineExcludesToday() {
        Fixture fixture = dailyStartedAt(
                "2026-08-05", "2026-08-06", 1, "2026-08-05T14:00:01Z"
        );

        PersonalProgressFacts facts = calculate(fixture, List.of(), "2026-08-05T14:30:00Z");

        assertAll(
                () -> assertFalse(facts.todayScheduled()),
                () -> assertEquals(1, facts.remainingOpportunityCount()),
                () -> assertTrue(facts.certificationDeadline().isEmpty())
        );
    }

    @Test
    void streakUsesScheduledOpportunitySequence() {
        Fixture fixture = daily("2026-08-01", "2026-08-03", 3);
        PersonalProgressFacts facts = calculate(
                fixture,
                List.of(
                        verification(fixture, "2026-08-01", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-02", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-03", VerificationStatus.APPROVED)
                ),
                "2026-08-03T10:00:00Z"
        );

        assertAll(
                () -> assertEquals(3, facts.currentStreak()),
                () -> assertEquals(0, facts.previousBestStreak())
        );
    }

    @Test
    void failedOpportunityClosesPreviousStreak() {
        Fixture fixture = daily("2026-08-01", "2026-08-03", 2);
        PersonalProgressFacts facts = calculate(
                fixture,
                List.of(
                        verification(fixture, "2026-08-01", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-03", VerificationStatus.APPROVED)
                ),
                "2026-08-03T10:00:00Z"
        );

        assertAll(
                () -> assertEquals(1, facts.currentStreak()),
                () -> assertEquals(1, facts.previousBestStreak())
        );
    }

    @Test
    void separatesPastFourStreakFromCurrentFiveStreak() {
        Fixture fixture = daily("2026-08-01", "2026-08-10", 9);
        List<Verification> approved = List.of(
                verification(fixture, "2026-08-01", VerificationStatus.APPROVED),
                verification(fixture, "2026-08-02", VerificationStatus.APPROVED),
                verification(fixture, "2026-08-03", VerificationStatus.APPROVED),
                verification(fixture, "2026-08-04", VerificationStatus.APPROVED),
                verification(fixture, "2026-08-06", VerificationStatus.APPROVED),
                verification(fixture, "2026-08-07", VerificationStatus.APPROVED),
                verification(fixture, "2026-08-08", VerificationStatus.APPROVED),
                verification(fixture, "2026-08-09", VerificationStatus.APPROVED),
                verification(fixture, "2026-08-10", VerificationStatus.APPROVED)
        );
        PersonalProgressFacts facts = calculate(fixture, approved, "2026-08-10T10:00:00Z");

        assertAll(
                () -> assertEquals(5, facts.currentStreak()),
                () -> assertEquals(4, facts.previousBestStreak())
        );
    }

    @Test
    void openTodayPreservesLiveStreak() {
        Fixture fixture = daily("2026-08-01", "2026-08-04", 3);
        PersonalProgressFacts facts = calculate(
                fixture,
                List.of(
                        verification(fixture, "2026-08-01", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-02", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-03", VerificationStatus.APPROVED)
                ),
                "2026-08-04T10:00:00Z"
        );

        assertAll(
                () -> assertEquals(3, facts.currentStreak()),
                () -> assertEquals(1, facts.remainingOpportunityCount())
        );
    }

    @Test
    void invalidatedOpportunityRecalculatesCountAndStreak() {
        Fixture fixture = daily("2026-08-01", "2026-08-03", 2);
        PersonalProgressFacts facts = calculate(
                fixture,
                List.of(
                        verification(fixture, "2026-08-01", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-02", VerificationStatus.INVALIDATED),
                        verification(fixture, "2026-08-03", VerificationStatus.APPROVED)
                ),
                "2026-08-03T10:00:00Z"
        );

        assertAll(
                () -> assertEquals(2, facts.completedCount()),
                () -> assertEquals(1, facts.currentStreak()),
                () -> assertEquals(1, facts.previousBestStreak())
        );
    }

    @ParameterizedTest
    @EnumSource(value = VerificationStatus.class, names = {"SUBMITTED", "PROCESSING", "REVIEW_REQUIRED"})
    void decisionPendingAfterDeadlineDoesNotBreakStreakOrRemainActionable(VerificationStatus status) {
        Fixture fixture = daily("2026-08-01", "2026-08-04", 3);
        PersonalProgressFacts facts = calculate(
                fixture,
                List.of(
                        verification(fixture, "2026-08-01", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-02", VerificationStatus.APPROVED),
                        verification(fixture, "2026-08-03", status)
                ),
                "2026-08-04T10:00:00Z"
        );

        assertAll(
                () -> assertEquals(2, facts.currentStreak()),
                () -> assertFalse(facts.todayVerificationPending()),
                () -> assertEquals(1, facts.pendingDecisionCount()),
                () -> assertEquals(1, facts.remainingOpportunityCount())
        );
    }

    @ParameterizedTest
    @EnumSource(value = VerificationStatus.class, names = {"REJECTED", "INVALIDATED"})
    void terminalFailureBreaksStreakBeforeDeadline(VerificationStatus status) {
        Fixture fixture = daily("2026-08-01", "2026-08-02", 2);
        List<Verification> verifications = List.of(
                verification(fixture, "2026-08-01", VerificationStatus.APPROVED),
                verification(fixture, "2026-08-02", status)
        );

        PersonalProgressFacts before = calculate(fixture, verifications, "2026-08-02T10:00:00Z");

        assertAll(
                () -> assertEquals(0, before.currentStreak()),
                () -> assertEquals(1, before.previousBestStreak()),
                () -> assertEquals(0, before.remainingOpportunityCount())
        );
    }

    @ParameterizedTest
    @EnumSource(value = VerificationStatus.class, names = {"REJECTED", "INVALIDATED"})
    void terminalFailureIsNotExposedAsFutureOpportunity(VerificationStatus status) {
        Fixture fixture = daily("2026-08-11", "2026-08-12", 1);
        Verification terminal = verification(fixture, "2026-08-12", status);

        PersonalProgressFacts facts = calculate(
                fixture,
                List.of(terminal),
                "2026-08-11T10:00:00Z"
        );

        assertEquals(1, facts.remainingOpportunityCount());
    }

    /** The ball is in the member's court, so the opportunity stays open until its deadline passes. */
    @Test
    void retryRequiredStaysOpenUntilTheDeadlineThenFails() {
        Fixture fixture = daily("2026-08-02", "2026-08-02", 1);
        Verification retry = verification(fixture, "2026-08-02", VerificationStatus.RETRY_REQUIRED);

        PersonalProgressFacts before = calculate(fixture, List.of(retry), "2026-08-02T10:00:00Z");
        PersonalProgressFacts after = calculate(fixture, List.of(retry), "2026-08-02T14:00:00Z");

        assertAll(
                () -> assertEquals(1, before.remainingOpportunityCount()),
                () -> assertEquals(0, before.pendingDecisionCount()),
                () -> assertFalse(before.todayVerificationPending()),
                () -> assertFalse(before.todayCompleted()),
                () -> assertEquals(0, after.remainingOpportunityCount()),
                () -> assertEquals(0, after.completedCount())
        );
    }

    @Test
    void pendingUploadIsOpenBeforeDeadlineAndFailedAfterDeadline() {
        Fixture fixture = daily("2026-08-02", "2026-08-02", 1);
        Verification pending = verification(fixture, "2026-08-02", VerificationStatus.PENDING_UPLOAD);

        PersonalProgressFacts before = calculate(fixture, List.of(pending), "2026-08-02T10:00:00Z");
        PersonalProgressFacts after = calculate(fixture, List.of(pending), "2026-08-02T14:00:00Z");

        assertAll(
                () -> assertEquals(1, before.remainingOpportunityCount()),
                () -> assertEquals(0, after.remainingOpportunityCount()),
                () -> assertEquals(0, before.pendingDecisionCount()),
                () -> assertEquals(0, after.pendingDecisionCount())
        );
    }

    @Test
    void rejectsVerificationOnUnscheduledDate() {
        Fixture fixture = specific("2026-08-10", "2026-08-16", 1, DayOfWeek.MONDAY);
        Verification invalid = verification(fixture, "2026-08-11", VerificationStatus.PENDING_UPLOAD);

        assertThrows(
                IllegalStateException.class,
                () -> calculate(fixture, List.of(invalid), "2026-08-11T10:00:00Z")
        );
    }

    @Test
    void rejectsVerificationBeforeParticipationBoundary() {
        Fixture fixture = dailyStartedAt(
                "2026-08-01", "2026-08-08", 3, "2026-08-05T00:00:00Z"
        );
        Verification beforeParticipation = verification(
                fixture,
                "2026-08-04",
                VerificationStatus.PENDING_UPLOAD
        );

        assertThrows(
                IllegalStateException.class,
                () -> calculate(fixture, List.of(beforeParticipation), "2026-08-08T10:00:00Z")
        );
    }

    @Test
    void rejectsFullProgressForJoinedMemberWithoutParticipationStart() {
        Fixture fixture = daily("2026-08-11", "2026-08-13", 3);
        GroupMember joined = new GroupMember(
                fixture.member().getRoutineGroup(),
                User.create(),
                GroupMemberRole.MEMBER,
                GroupMemberStatus.JOINED,
                Instant.parse("2026-08-01T09:00:00Z")
        );

        assertThrows(
                IllegalStateException.class,
                () -> calculator.calculate(joined, fixture.schedule(), List.of(), clock("2026-08-11T10:00:00Z"))
        );
    }

    @Test
    void rejectsVerificationFromAnotherProgressTarget() {
        Fixture target = daily("2026-08-11", "2026-08-13", 3);
        Fixture other = daily("2026-08-11", "2026-08-13", 3);
        Verification invalid = verification(other, "2026-08-11", VerificationStatus.PENDING_UPLOAD);

        assertThrows(
                IllegalStateException.class,
                () -> calculate(target, List.of(invalid), "2026-08-11T10:00:00Z")
        );
    }

    @Test
    void rejectsDuplicateVerificationInput() {
        Fixture fixture = daily("2026-08-11", "2026-08-13", 3);
        Verification first = verification(fixture, "2026-08-11", VerificationStatus.PENDING_UPLOAD);
        Verification duplicate = verification(fixture, "2026-08-11", VerificationStatus.PROCESSING);

        assertThrows(
                IllegalStateException.class,
                () -> calculate(fixture, List.of(first, duplicate), "2026-08-11T10:00:00Z")
        );
    }

    @Test
    void rejectsApprovedFutureOpportunity() {
        Fixture fixture = daily("2026-08-11", "2026-08-13", 3);
        Verification future = verification(fixture, "2026-08-12", VerificationStatus.APPROVED);

        assertThrows(
                IllegalStateException.class,
                () -> calculate(fixture, List.of(future), "2026-08-11T10:00:00Z")
        );
    }

    @Test
    void rejectsRequiredCountGreaterThanScheduledOpportunityCount() {
        Fixture fixture = daily("2026-08-11", "2026-08-13", 4);

        assertThrows(
                IllegalStateException.class,
                () -> calculate(fixture, List.of(), "2026-08-11T10:00:00Z")
        );
    }

    private PersonalProgressFacts calculate(Fixture fixture, List<Verification> verifications, String now) {
        return calculator.calculate(fixture.member(), fixture.schedule(), verifications, clock(now));
    }

    private Verification verification(Fixture fixture, String scheduledDate, VerificationStatus status) {
        Verification verification = Verification.create(
                fixture.member(),
                fixture.schedule(),
                LocalDate.parse(scheduledDate)
        );
        switch (status) {
            case PENDING_UPLOAD -> {
            }
            case SUBMITTED -> verification.submit(TRANSITION_CLOCK);
            case PROCESSING -> {
                verification.submit(TRANSITION_CLOCK);
                verification.startProcessing();
            }
            case APPROVED -> {
                verification.submit(TRANSITION_CLOCK);
                verification.startProcessing();
                verification.approve(TRANSITION_CLOCK);
            }
            case REVIEW_REQUIRED -> {
                verification.submit(TRANSITION_CLOCK);
                verification.startProcessing();
                verification.requestReview();
            }
            case RETRY_REQUIRED -> {
                verification.submit(TRANSITION_CLOCK);
                verification.startProcessing();
                verification.requestRetry();
            }
            case REJECTED -> {
                verification.submit(TRANSITION_CLOCK);
                verification.startProcessing();
                verification.reject();
            }
            case INVALIDATED -> {
                verification.submit(TRANSITION_CLOCK);
                verification.startProcessing();
                verification.approve(TRANSITION_CLOCK);
                verification.invalidate(TRANSITION_CLOCK);
            }
        }
        return verification;
    }

    private Fixture daily(String startDate, String endDate, int requiredCount) {
        return dailyStartedAt(startDate, endDate, requiredCount, "2026-08-01T09:00:00Z");
    }

    private Fixture dailyStartedAt(
            String startDate,
            String endDate,
            int requiredCount,
            String participationStartedAt
    ) {
        return fixture(
                ScheduleType.DAILY,
                startDate,
                endDate,
                requiredCount,
                Set.of(),
                Instant.parse(participationStartedAt)
        );
    }

    private Fixture specific(String startDate, String endDate, int requiredCount, DayOfWeek... days) {
        return fixture(
                ScheduleType.SPECIFIC_DAYS,
                startDate,
                endDate,
                requiredCount,
                Set.of(days),
                Instant.parse("2026-08-01T09:00:00Z")
        );
    }

    private Fixture fixture(
            ScheduleType type,
            String startDate,
            String endDate,
            int requiredCount,
            Set<DayOfWeek> days,
            Instant participationStartedAt
    ) {
        User user = User.create();
        RoutineGroup group = new RoutineGroup(
                new RoutineDefinition("물 마시기", null),
                user,
                "건강한 물 마시기",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.ACTIVE,
                5,
                requiredCount
        );
        RoutineSchedule schedule = new RoutineSchedule(
                group,
                type,
                LocalDate.parse(startDate),
                LocalDate.parse(endDate),
                LocalTime.of(23, 0),
                "Asia/Seoul",
                days
        );
        GroupMember member = new GroupMember(
                group,
                user,
                GroupMemberRole.MEMBER,
                GroupMemberStatus.JOINED,
                Instant.parse("2026-08-01T09:00:00Z")
        );
        member.startParticipation(participationStartedAt);
        return new Fixture(member, schedule);
    }

    private static Clock clock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private record Fixture(GroupMember member, RoutineSchedule schedule) {
    }
}

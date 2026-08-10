package com.allog.progress;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.progress.domain.ParticipationCompletionEvaluation;
import com.allog.progress.domain.ParticipationCompletionEvaluation.Outcome;
import com.allog.progress.domain.PersonalProgressFacts;
import com.allog.progress.service.ParticipationCompletionEvaluator;
import com.allog.progress.service.PersonalProgressCalculator;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import com.allog.verification.domain.Verification;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticipationCompletionEvaluatorTest {

    private static final Clock TRANSITION_CLOCK = clock("2026-08-01T00:00:00Z");

    private final ParticipationCompletionEvaluator evaluator = new ParticipationCompletionEvaluator();
    private final PersonalProgressCalculator personalProgressCalculator = new PersonalProgressCalculator();

    @Test
    void goalAchievementBeforeFinalDeadlineIsNotFinalizable() {
        Fixture fixture = daily("2026-08-10", "2026-08-12", 2);

        ParticipationCompletionEvaluation result = evaluator.evaluate(
                facts(2, 2, 0), fixture.schedule(), clock("2026-08-12T13:59:59Z")
        );

        assertAll(
                () -> assertTrue(result.goalAchieved()),
                () -> assertFalse(result.scheduleEnded()),
                () -> assertFalse(result.finalizationReady()),
                () -> assertTrue(result.recommendedOutcome().isEmpty())
        );
    }

    @Test
    void recommendsCompletedAtExactFinalDeadlineWithoutChangingMemberState() {
        Fixture fixture = daily("2026-08-10", "2026-08-12", 2);

        ParticipationCompletionEvaluation result = evaluator.evaluate(
                facts(2, 2, 0), fixture.schedule(), clock("2026-08-12T14:00:00Z")
        );

        assertAll(
                () -> assertTrue(result.scheduleEnded()),
                () -> assertTrue(result.finalizationReady()),
                () -> assertEquals(Outcome.COMPLETED, result.recommendedOutcome().orElseThrow()),
                () -> assertEquals(GroupMemberStatus.ACTIVE, fixture.member().getStatus()),
                () -> assertEquals(RoutineGroupStatus.ACTIVE, fixture.schedule().getRoutineGroup().getStatus())
        );
    }

    @Test
    void recommendsFailedAfterScheduleEndsBelowRequirement() {
        Fixture fixture = daily("2026-08-10", "2026-08-12", 2);

        ParticipationCompletionEvaluation result = evaluator.evaluate(
                facts(1, 2, 0), fixture.schedule(), clock("2026-08-12T14:00:01Z")
        );

        assertAll(
                () -> assertFalse(result.goalAchieved()),
                () -> assertTrue(result.finalizationReady()),
                () -> assertEquals(Outcome.FAILED, result.recommendedOutcome().orElseThrow())
        );
    }

    @Test
    void pendingDecisionBlocksFailureAfterScheduleEnds() {
        Fixture fixture = daily("2026-08-10", "2026-08-12", 2);

        ParticipationCompletionEvaluation result = evaluator.evaluate(
                facts(1, 2, 1), fixture.schedule(), clock("2026-08-12T14:00:01Z")
        );

        assertAll(
                () -> assertTrue(result.scheduleEnded()),
                () -> assertTrue(result.hasPendingDecision()),
                () -> assertFalse(result.finalizationReady()),
                () -> assertTrue(result.recommendedOutcome().isEmpty())
        );
    }

    @Test
    void specificDaysScheduleEndsAtFridayDeadlineRatherThanEndDate() {
        Fixture fixture = specific(
                "2026-08-10",
                "2026-08-16",
                3,
                DayOfWeek.MONDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY
        );

        ParticipationCompletionEvaluation result = evaluator.evaluate(
                facts(3, 3, 0), fixture.schedule(), clock("2026-08-14T14:00:00Z")
        );

        assertAll(
                () -> assertEquals(Instant.parse("2026-08-14T14:00:00Z"),
                        result.finalScheduledDeadline()),
                () -> assertTrue(result.scheduleEnded()),
                () -> assertEquals(Outcome.COMPLETED, result.recommendedOutcome().orElseThrow())
        );
    }

    @Test
    void pendingApprovalMakesEndedParticipationFinalizableOnReevaluation() {
        Fixture fixture = daily("2026-08-10", "2026-08-11", 2);
        Verification first = approved(fixture, "2026-08-10");
        Verification pending = processing(fixture, "2026-08-11");
        Clock afterEnd = clock("2026-08-11T14:30:00Z");

        PersonalProgressFacts beforeFacts = personalProgressCalculator.calculate(
                fixture.member(), fixture.schedule(), List.of(first, pending), afterEnd
        );
        ParticipationCompletionEvaluation before = evaluator.evaluate(beforeFacts, fixture.schedule(), afterEnd);
        pending.approve(TRANSITION_CLOCK);
        PersonalProgressFacts afterFacts = personalProgressCalculator.calculate(
                fixture.member(), fixture.schedule(), List.of(first, pending), afterEnd
        );
        ParticipationCompletionEvaluation after = evaluator.evaluate(afterFacts, fixture.schedule(), afterEnd);

        assertAll(
                () -> assertFalse(before.finalizationReady()),
                () -> assertTrue(before.hasPendingDecision()),
                () -> assertEquals(0, afterFacts.pendingDecisionCount()),
                () -> assertEquals(2, afterFacts.completedCount()),
                () -> assertTrue(after.finalizationReady()),
                () -> assertEquals(Outcome.COMPLETED, after.recommendedOutcome().orElseThrow())
        );
    }

    @Test
    void invalidationRecalculatesCompletedEvaluationAsFailed() {
        Fixture fixture = daily("2026-08-10", "2026-08-11", 2);
        Verification first = approved(fixture, "2026-08-10");
        Verification second = approved(fixture, "2026-08-11");
        Clock afterEnd = clock("2026-08-11T14:30:00Z");

        PersonalProgressFacts beforeFacts = personalProgressCalculator.calculate(
                fixture.member(), fixture.schedule(), List.of(first, second), afterEnd
        );
        ParticipationCompletionEvaluation before = evaluator.evaluate(beforeFacts, fixture.schedule(), afterEnd);
        second.invalidate(TRANSITION_CLOCK);
        PersonalProgressFacts afterFacts = personalProgressCalculator.calculate(
                fixture.member(), fixture.schedule(), List.of(first, second), afterEnd
        );
        ParticipationCompletionEvaluation after = evaluator.evaluate(afterFacts, fixture.schedule(), afterEnd);

        assertAll(
                () -> assertEquals(Outcome.COMPLETED, before.recommendedOutcome().orElseThrow()),
                () -> assertFalse(after.goalAchieved()),
                () -> assertEquals(1, afterFacts.completedCount()),
                () -> assertEquals(Outcome.FAILED, after.recommendedOutcome().orElseThrow())
        );
    }

    @Test
    void rejectsScheduleWithoutAnyActualOpportunity() {
        Fixture fixture = specific(
                "2026-08-11", "2026-08-12", 1, DayOfWeek.MONDAY
        );

        assertThrows(
                IllegalStateException.class,
                () -> evaluator.evaluate(facts(0, 1, 0), fixture.schedule(), TRANSITION_CLOCK)
        );
    }

    private PersonalProgressFacts facts(int completed, int required, int pending) {
        return new PersonalProgressFacts(
                false,
                false,
                false,
                completed,
                required,
                0,
                0,
                0,
                pending,
                Optional.empty(),
                GroupMemberStatus.ACTIVE
        );
    }

    private Verification approved(Fixture fixture, String date) {
        Verification verification = processing(fixture, date);
        verification.approve(TRANSITION_CLOCK);
        return verification;
    }

    private Verification processing(Fixture fixture, String date) {
        Verification verification = Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.parse(date)
        );
        verification.submit(TRANSITION_CLOCK);
        verification.startProcessing();
        return verification;
    }

    private Fixture daily(String startDate, String endDate, int requiredCount) {
        return fixture(ScheduleType.DAILY, startDate, endDate, requiredCount, Set.of());
    }

    private Fixture specific(
            String startDate,
            String endDate,
            int requiredCount,
            DayOfWeek... days
    ) {
        return fixture(ScheduleType.SPECIFIC_DAYS, startDate, endDate, requiredCount, Set.of(days));
    }

    private Fixture fixture(
            ScheduleType scheduleType,
            String startDate,
            String endDate,
            int requiredCount,
            Set<DayOfWeek> days
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
                scheduleType,
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
                GroupMemberStatus.ACTIVE,
                LocalDateTime.of(2026, 8, 1, 9, 0)
        );
        return new Fixture(member, schedule);
    }

    private static Clock clock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private record Fixture(GroupMember member, RoutineSchedule schedule) {
    }
}

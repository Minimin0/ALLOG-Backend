package com.allog.ai.coaching;

import com.allog.ai.coaching.analyzer.ProgressAnalyzer;
import com.allog.ai.coaching.detector.ProgressInsightDetector;
import com.allog.ai.coaching.domain.CompletionRiskLevel;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.ProgressInsight;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.dto.CoachContext;
import com.allog.ai.coaching.dto.ProgressAnalysisInput;
import com.allog.ai.coaching.dto.ProgressSnapshot;
import com.allog.ai.coaching.policy.AiCoachPolicy;
import com.allog.ai.coaching.selector.InsightSelector;
import com.allog.ai.coaching.selector.RoutineStateResolver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoachingDomainTest {

    private static final Instant NOW = Instant.parse("2026-08-07T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    private static final AiCoachPolicy POLICY = AiCoachPolicy.defaults();
    private final ProgressAnalyzer analyzer = new ProgressAnalyzer();
    private final ProgressInsightDetector detector = new ProgressInsightDetector();

    @Nested
    class ProgressAnalysis {

        @Test
        void calculatesRemainingRequiredCountFromExplicitOpportunityFacts() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .required(5).completed(3).opportunities(3));

            assertAll(
                    () -> assertEquals(2, snapshot.remainingRequiredCount()),
                    () -> assertEquals(3, snapshot.remainingOpportunityCount()),
                    () -> assertEquals(3, snapshot.potentialCompletionCapacity()),
                    () -> assertEquals(0.6, snapshot.personalCompletionRate())
            );
        }

        @Test
        void includesPendingDecisionsInTightCompletionCapacity() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .required(5).completed(3).pendingDecisions(1).opportunities(1));

            assertAll(
                    () -> assertEquals(2, snapshot.remainingRequiredCount()),
                    () -> assertEquals(2, snapshot.potentialCompletionCapacity()),
                    () -> assertEquals(CompletionRiskLevel.HIGH, snapshot.completionRiskLevel())
            );
        }

        @Test
        void remainsHighWhenPendingAndOpenCannotReachRequirement() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .required(6).completed(3).pendingDecisions(1).opportunities(1));

            assertAll(
                    () -> assertEquals(3, snapshot.remainingRequiredCount()),
                    () -> assertEquals(2, snapshot.potentialCompletionCapacity()),
                    () -> assertEquals(CompletionRiskLevel.HIGH, snapshot.completionRiskLevel())
            );
        }

        @Test
        void pendingCapacityPreventsFalseHighRisk() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .required(5).completed(3).pendingDecisions(1).opportunities(2));

            assertAll(
                    () -> assertEquals(2, snapshot.remainingRequiredCount()),
                    () -> assertEquals(3, snapshot.potentialCompletionCapacity()),
                    () -> assertEquals(CompletionRiskLevel.LOW, snapshot.completionRiskLevel())
            );
        }

        @Test
        void marksHighRiskWhenEveryRemainingOpportunityMustSucceed() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .required(5).completed(3).opportunities(2));

            assertEquals(CompletionRiskLevel.HIGH, snapshot.completionRiskLevel());
        }

        @Test
        void marksHighRiskWhenThreeCompletionsRemainWithTwoOpportunities() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .required(5).completed(2).opportunities(2));

            assertEquals(CompletionRiskLevel.HIGH, snapshot.completionRiskLevel());
        }

        @Test
        void marksHighRiskWhenCompletionIsMathematicallyImpossible() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .required(5).completed(2).opportunities(1));

            assertEquals(CompletionRiskLevel.HIGH, snapshot.completionRiskLevel());
        }

        @Test
        void marksMediumRiskUsingPolicyRatio() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .required(5).completed(2).opportunities(4));

            assertEquals(CompletionRiskLevel.MEDIUM, snapshot.completionRiskLevel());
        }

        @Test
        void rejectsInvalidCountsAndRates() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new InputFixture().completed(-1).build()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new InputFixture().required(0).build()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new InputFixture().opportunities(-1).build()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new InputFixture().pendingDecisions(-1).build()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new InputFixture().todayPending(true).build()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new InputFixture().currentStreak(-1).build()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new InputFixture().previousBestStreak(-1).build()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new InputFixture().groupRate(1.01).build()),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new InputFixture().previousRate(-0.01).build())
            );
        }
    }

    @Nested
    class InsightDetection {

        @Test
        void detectsDeadlineApproachingSixtyMinutesBeforeDeadline() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .todayCompleted(false).deadline(NOW.plusSeconds(60 * 60)));

            assertTrue(has(insights, InsightType.DEADLINE_APPROACHING));
        }

        @Test
        void doesNotDetectDeadlineApproachingAfterTodayCompletion() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .todayCompleted(true).deadline(NOW.plusSeconds(60 * 60)));

            assertFalse(has(insights, InsightType.DEADLINE_APPROACHING));
        }

        @Test
        void doesNotDetectDeadlineApproachingAfterDeadlinePassed() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .todayCompleted(false).deadline(NOW.minusSeconds(1)));
            List<ProgressInsight> insights = detector.detect(snapshot, POLICY);

            assertAll(
                    () -> assertTrue(snapshot.deadlinePassed()),
                    () -> assertEquals(0, snapshot.minutesUntilDeadline()),
                    () -> assertFalse(has(insights, InsightType.DEADLINE_APPROACHING))
            );
        }

        @Test
        void doesNotCreateTodayInsightsWhenTodayIsNotScheduled() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .todayScheduled(false)
                    .todayCompleted(false)
                    .deadline(NOW.plusSeconds(60 * 60)));

            assertAll(
                    () -> assertFalse(has(insights, InsightType.TODAY_NOT_COMPLETED)),
                    () -> assertFalse(has(insights, InsightType.DEADLINE_APPROACHING))
            );
        }

        @Test
        void pendingTodaySuppressesActionRequiredInsights() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .todayCompleted(false)
                    .todayPending(true)
                    .pendingDecisions(1)
                    .deadline(NOW.plusSeconds(60 * 60)));

            assertAll(
                    () -> assertTrue(has(insights, InsightType.VERIFICATION_PENDING)),
                    () -> assertFalse(has(insights, InsightType.TODAY_NOT_COMPLETED)),
                    () -> assertFalse(has(insights, InsightType.DEADLINE_APPROACHING)),
                    () -> assertEquals(
                            InsightType.VERIFICATION_PENDING,
                            new InsightSelector().select(insights).orElseThrow().type()
                    )
            );
        }

        @Test
        void detectsNewStreakAgainstPreviousBest() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .currentStreak(5).previousBestStreak(4));

            assertTrue(has(insights, InsightType.STREAK_RECORD));
        }

        @Test
        void doesNotTreatFirstSuccessAsStreakRecord() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .currentStreak(1).previousBestStreak(0));

            assertFalse(has(insights, InsightType.STREAK_RECORD));
        }

        @Test
        void doesNotDetectStreakRecordWhenCurrentEqualsPreviousBest() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .currentStreak(5).previousBestStreak(5));

            assertFalse(has(insights, InsightType.STREAK_RECORD));
        }

        @Test
        void detectsContinuingStreakAtPolicyThreshold() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .currentStreak(POLICY.streakContinuingCount()));

            assertTrue(has(insights, InsightType.STREAK_CONTINUING));
        }

        @Test
        void detectsGroupGoalFromUpstreamRate() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .groupRate(POLICY.groupGoalNearRate()));

            assertTrue(has(insights, InsightType.GROUP_GOAL_NEAR));
        }

        @Test
        void detectsImprovementOverPreviousChallenge() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .required(5).completed(4).previousRate(0.6));

            assertTrue(has(insights, InsightType.IMPROVED_FROM_PREVIOUS));
        }

        @Test
        void selectsDeadlineApproachingFromMultipleInsights() {
            List<ProgressInsight> insights = detect(new InputFixture()
                    .todayCompleted(false)
                    .deadline(NOW.plusSeconds(60 * 60))
                    .currentStreak(POLICY.streakContinuingCount()));

            Optional<ProgressInsight> selected = new InsightSelector().select(insights);

            assertAll(
                    () -> assertTrue(has(insights, InsightType.DEADLINE_APPROACHING)),
                    () -> assertTrue(has(insights, InsightType.STREAK_CONTINUING)),
                    () -> assertTrue(has(insights, InsightType.TODAY_NOT_COMPLETED)),
                    () -> assertEquals(InsightType.DEADLINE_APPROACHING, selected.orElseThrow().type())
            );
        }

        @Test
        void returnsEmptySelectionWhenThereIsNoInsight() {
            assertTrue(new InsightSelector().select(List.of()).isEmpty());
        }
    }

    @Nested
    class RoutineStateAndContext {

        private final RoutineStateResolver resolver = new RoutineStateResolver();

        @Test
        void resolvesHighRiskAsAtRisk() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .required(5).completed(2).opportunities(2));

            assertEquals(RoutineState.AT_RISK, resolve(snapshot));
        }

        @Test
        void resolvesDeadlineApproachingAsAttention() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .todayCompleted(false).deadline(NOW.plusSeconds(60 * 60)));

            assertEquals(RoutineState.ATTENTION, resolve(snapshot));
        }

        @Test
        void resolvesCompletedChallengeBeforeOtherStates() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .challengeCompleted(true).required(5).completed(2).opportunities(1));

            assertAll(
                    () -> assertEquals(RoutineState.COMPLETED, resolve(snapshot)),
                    () -> assertTrue(detector.detect(snapshot, POLICY).isEmpty())
            );
        }

        @Test
        void resolvesSafeProgressAsGood() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .groupRate(null).previousRate(null));

            assertEquals(RoutineState.GOOD, resolve(snapshot));
        }

        @Test
        void pendingVerificationAloneDoesNotWorsenRoutineState() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .todayCompleted(false)
                    .todayPending(true)
                    .pendingDecisions(1)
                    .groupRate(null)
                    .previousRate(null));

            assertEquals(RoutineState.GOOD, resolve(snapshot));
        }

        @Test
        void createsCoachContextWithoutPersonalIdentifiers() {
            ProgressSnapshot snapshot = analyze(new InputFixture()
                    .todayCompleted(false).deadline(NOW.plusSeconds(60 * 60)));
            List<ProgressInsight> insights = detector.detect(snapshot, POLICY);
            Optional<ProgressInsight> selected = new InsightSelector().select(insights);
            RoutineState state = resolver.resolve(snapshot, insights);

            CoachContext context = CoachContext.from("뮞일 물 마시기", snapshot, selected, state);
            Set<String> fieldNames = Stream.of(
                            CoachContext.class,
                            CoachContext.Challenge.class,
                            CoachContext.Progress.class,
                            CoachContext.Group.class,
                            CoachContext.Deadline.class,
                            CoachContext.SelectedInsight.class
                    )
                    .flatMap(type -> Stream.of(type.getRecordComponents()))
                    .map(component -> component.getName().toLowerCase())
                    .collect(java.util.stream.Collectors.toSet());

            assertAll(
                    () -> assertEquals("뮞일 물 마시기", context.challenge().name()),
                    () -> assertEquals(InsightType.DEADLINE_APPROACHING, context.insight().type()),
                    () -> assertEquals(RoutineState.ATTENTION, context.routineState()),
                    () -> assertEquals(0, context.progress().pendingDecisionCount()),
                    () -> assertFalse(context.progress().todayVerificationPending()),
                    () -> assertFalse(fieldNames.stream().anyMatch(name ->
                            name.contains("userid")
                                    || name.contains("email")
                                    || name.contains("phone")
                                    || name.contains("token")
                                    || name.contains("participationid")))
            );
        }
    }

    private ProgressSnapshot analyze(InputFixture fixture) {
        return analyzer.analyze(fixture.build(), POLICY, CLOCK);
    }

    private List<ProgressInsight> detect(InputFixture fixture) {
        return detector.detect(analyze(fixture), POLICY);
    }

    private RoutineState resolve(ProgressSnapshot snapshot) {
        return new RoutineStateResolver().resolve(snapshot, detector.detect(snapshot, POLICY));
    }

    private boolean has(List<ProgressInsight> insights, InsightType type) {
        return insights.stream().anyMatch(insight -> insight.type() == type);
    }

    private static final class InputFixture {
        private boolean todayScheduled = true;
        private boolean todayCompleted = true;
        private boolean todayVerificationPending;
        private int completedCount = 3;
        private int requiredCompletionCount = 5;
        private int currentStreak = 1;
        private int previousBestStreak = 4;
        private int remainingOpportunityCount = 4;
        private int pendingDecisionCount;
        private Double groupCompletionRate = 0.4;
        private Double previousChallengeCompletionRate = 0.7;
        private Instant certificationDeadline;
        private boolean challengeCompleted;

        private InputFixture todayScheduled(boolean value) {
            todayScheduled = value;
            return this;
        }

        private InputFixture todayCompleted(boolean value) {
            todayCompleted = value;
            return this;
        }

        private InputFixture todayPending(boolean value) {
            todayVerificationPending = value;
            return this;
        }

        private InputFixture completed(int value) {
            completedCount = value;
            return this;
        }

        private InputFixture required(int value) {
            requiredCompletionCount = value;
            return this;
        }

        private InputFixture currentStreak(int value) {
            currentStreak = value;
            return this;
        }

        private InputFixture previousBestStreak(int value) {
            previousBestStreak = value;
            return this;
        }

        private InputFixture opportunities(int value) {
            remainingOpportunityCount = value;
            return this;
        }

        private InputFixture pendingDecisions(int value) {
            pendingDecisionCount = value;
            return this;
        }

        private InputFixture groupRate(Double value) {
            groupCompletionRate = value;
            return this;
        }

        private InputFixture previousRate(Double value) {
            previousChallengeCompletionRate = value;
            return this;
        }

        private InputFixture deadline(Instant value) {
            certificationDeadline = value;
            return this;
        }

        private InputFixture challengeCompleted(boolean value) {
            challengeCompleted = value;
            return this;
        }

        private ProgressAnalysisInput build() {
            return new ProgressAnalysisInput(
                    todayScheduled,
                    todayCompleted,
                    todayVerificationPending,
                    completedCount,
                    requiredCompletionCount,
                    currentStreak,
                    previousBestStreak,
                    remainingOpportunityCount,
                    pendingDecisionCount,
                    groupCompletionRate,
                    previousChallengeCompletionRate,
                    certificationDeadline,
                    challengeCompleted
            );
        }
    }
}

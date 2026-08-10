package com.allog.ai.coaching.production;

import com.allog.ai.coaching.domain.ActionType;
import com.allog.ai.coaching.domain.GenerationType;
import com.allog.ai.coaching.domain.InsightType;
import com.allog.ai.coaching.domain.RoutineState;
import com.allog.ai.coaching.dto.AiCoachText;
import com.allog.ai.coaching.dto.CoachContext;
import com.allog.ai.coaching.provider.AiCoachProvider;
import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.service.RoutineGroupActivationService;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import com.allog.verification.domain.Verification;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:production-ai-coach;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Import(ProductionAiCoachIntegrationTest.TestConfig.class)
class ProductionAiCoachIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private ProductionAiCoachApplicationService applicationService;

    @Autowired
    private RoutineGroupActivationService activationService;

    @Autowired
    private TrackingProvider provider;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        provider.reset();
    }

    @Test
    void generatesFromProductionFactsWithFixedDenominatorAndNoAiTransaction() {
        Fixture fixture = activeFixture(
                GroupMemberStatus.JOINED,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.LEFT
        );
        addApproved(fixture, 0, 5);
        addApproved(fixture, 1, 3);

        ProductionAiCoachResult result = applicationService.generateFor(
                fixture.groupId(),
                fixture.members().getFirst().userId()
        );

        assertEquals(GroupMemberStatus.ACTIVE, result.participationStatus());
        assertEquals(InsightType.GROUP_GOAL_NEAR, result.insightType());
        assertEquals(ActionType.OPEN_GROUP, result.actionType());
        assertEquals(1, provider.calls());
        assertFalse(provider.transactionActive());
        assertEquals("아침 물 마시기", provider.context().challenge().name());
        assertEquals(0.8, provider.context().group().completionRate());
    }

    @ParameterizedTest
    @MethodSource("formerMemberStatuses")
    void keepsStartedFormerMemberInDenominatorButDeniesOwnCoach(GroupMemberStatus formerStatus) {
        Fixture fixture = activeFixture(GroupMemberStatus.JOINED, GroupMemberStatus.JOINED);
        addApproved(fixture, 0, 5);
        jdbcTemplate.update(
                "UPDATE group_member SET status = ? WHERE id = ?",
                formerStatus.name(),
                fixture.members().get(1).memberId()
        );

        applicationService.generateFor(fixture.groupId(), fixture.members().getFirst().userId());

        assertEquals(0.5, provider.context().group().completionRate());
        provider.reset();
        assertThrows(
                AiCoachAccessDeniedException.class,
                () -> applicationService.generateFor(
                        fixture.groupId(),
                        fixture.members().get(1).userId()
                )
        );
        assertEquals(0, provider.calls());
    }

    @Test
    void mapsPendingProductionVerificationToProgressAction() {
        Fixture fixture = activeFixture(GroupMemberStatus.JOINED);
        addPendingToday(fixture, 0);

        ProductionAiCoachResult result = applicationService.generateFor(
                fixture.groupId(),
                fixture.members().getFirst().userId()
        );

        assertEquals(InsightType.VERIFICATION_PENDING, result.insightType());
        assertEquals(ActionType.OPEN_PROGRESS, result.actionType());
        assertEquals(GenerationType.AI, result.generationType());
        assertTrue(provider.context().progress().todayVerificationPending());
        assertEquals(1, provider.context().progress().pendingDecisionCount());
        assertFalse(provider.transactionActive());
    }

    @ParameterizedTest
    @MethodSource("lifecycleCases")
    void returnsLifecycleResultWithoutScheduleOrProvider(
            RoutineGroupStatus groupStatus,
            GroupMemberStatus memberStatus,
            ActionType actionType,
            RoutineState routineState
    ) {
        Fixture fixture = lifecycleFixture(groupStatus, memberStatus);

        ProductionAiCoachResult result = applicationService.generateFor(
                fixture.groupId(),
                fixture.members().getFirst().userId()
        );

        assertEquals(memberStatus, result.participationStatus());
        assertEquals(actionType, result.actionType());
        assertEquals(routineState, result.routineState());
        assertEquals(GenerationType.TEMPLATE, result.generationType());
        assertNull(result.insightType());
        assertEquals(0, provider.calls());
    }

    @Test
    void rejectsActivePersistenceInconsistencyBeforeProvider() {
        Fixture fixture = lifecycleFixture(RoutineGroupStatus.ACTIVE, GroupMemberStatus.ACTIVE);

        assertThrows(
                IllegalStateException.class,
                () -> applicationService.generateFor(
                        fixture.groupId(),
                        fixture.members().getFirst().userId()
                )
        );

        assertEquals(0, provider.calls());
    }

    private Fixture activeFixture(GroupMemberStatus... statuses) {
        Fixture fixture = fixture(RoutineGroupStatus.RECRUITING, true, statuses);
        activationService.activate(fixture.groupId(), CLOCK);
        return fixture;
    }

    private Fixture lifecycleFixture(RoutineGroupStatus groupStatus, GroupMemberStatus status) {
        return fixture(groupStatus, false, status);
    }

    private Fixture fixture(
            RoutineGroupStatus groupStatus,
            boolean withSchedule,
            GroupMemberStatus... memberStatuses
    ) {
        return inTransaction(() -> {
            User owner = User.create();
            RoutineDefinition definition = new RoutineDefinition("물 마시기", null);
            entityManager.persist(owner);
            entityManager.persist(definition);
            RoutineGroup group = new RoutineGroup(
                    definition,
                    owner,
                    "아침 물 마시기",
                    GroupVisibility.PUBLIC,
                    groupStatus,
                    10,
                    5
            );
            entityManager.persist(group);

            Long scheduleId = null;
            if (withSchedule) {
                RoutineSchedule schedule = new RoutineSchedule(
                        group,
                        ScheduleType.DAILY,
                        LocalDate.of(2026, 8, 7),
                        LocalDate.of(2026, 8, 11),
                        LocalTime.of(23, 0),
                        "Asia/Seoul",
                        Set.of()
                );
                entityManager.persist(schedule);
                entityManager.flush();
                scheduleId = schedule.getId();
            }

            List<MemberRef> members = new ArrayList<>();
            for (int index = 0; index < memberStatuses.length; index++) {
                User user = index == 0 ? owner : User.create();
                if (index > 0) {
                    entityManager.persist(user);
                }
                GroupMember member = new GroupMember(
                        group,
                        user,
                        index == 0 ? GroupMemberRole.OWNER : GroupMemberRole.MEMBER,
                        memberStatuses[index],
                        LocalDateTime.of(2026, 8, 1, 9, index)
                );
                entityManager.persist(member);
                entityManager.flush();
                members.add(new MemberRef(member.getId(), user.getId()));
            }
            return new Fixture(group.getId(), scheduleId, List.copyOf(members));
        });
    }

    private void addApproved(Fixture fixture, int memberIndex, int count) {
        inTransaction(() -> {
            GroupMember member = entityManager.find(
                    GroupMember.class,
                    fixture.members().get(memberIndex).memberId()
            );
            RoutineSchedule schedule = entityManager.find(RoutineSchedule.class, fixture.scheduleId());
            for (int index = 0; index < count; index++) {
                Verification verification = Verification.create(
                        member,
                        schedule,
                        LocalDate.of(2026, 8, 7).plusDays(index)
                );
                verification.submit(CLOCK);
                verification.startProcessing();
                verification.approve(CLOCK);
                entityManager.persist(verification);
            }
            entityManager.flush();
            return null;
        });
    }

    private void addPendingToday(Fixture fixture, int memberIndex) {
        inTransaction(() -> {
            GroupMember member = entityManager.find(
                    GroupMember.class,
                    fixture.members().get(memberIndex).memberId()
            );
            RoutineSchedule schedule = entityManager.find(RoutineSchedule.class, fixture.scheduleId());
            Verification verification = Verification.create(member, schedule, LocalDate.of(2026, 8, 11));
            verification.submit(CLOCK);
            entityManager.persist(verification);
            entityManager.flush();
            return null;
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private static Stream<Arguments> lifecycleCases() {
        return Stream.of(
                Arguments.of(
                        RoutineGroupStatus.RECRUITING,
                        GroupMemberStatus.JOINED,
                        ActionType.OPEN_GROUP,
                        null
                ),
                Arguments.of(
                        RoutineGroupStatus.ACTIVE,
                        GroupMemberStatus.COMPLETED,
                        ActionType.OPEN_PROGRESS,
                        RoutineState.COMPLETED
                ),
                Arguments.of(
                        RoutineGroupStatus.ACTIVE,
                        GroupMemberStatus.FAILED,
                        ActionType.OPEN_PROGRESS,
                        null
                )
        );
    }

    private static Stream<Arguments> formerMemberStatuses() {
        return Stream.of(
                Arguments.of(GroupMemberStatus.LEFT),
                Arguments.of(GroupMemberStatus.REMOVED)
        );
    }

    record Fixture(Long groupId, Long scheduleId, List<MemberRef> members) {
    }

    record MemberRef(Long memberId, Long userId) {
    }

    static final class TrackingProvider implements AiCoachProvider {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile CoachContext context;
        private volatile boolean transactionActive;

        @Override
        public AiCoachText generate(CoachContext context) {
            calls.incrementAndGet();
            this.context = context;
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            return new AiCoachText("생성 제목", "생성 메시지");
        }

        int calls() {
            return calls.get();
        }

        CoachContext context() {
            return context;
        }

        boolean transactionActive() {
            return transactionActive;
        }

        void reset() {
            calls.set(0);
            context = null;
            transactionActive = false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        Clock productionAiCoachTestClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        TrackingProvider trackingAiCoachProvider() {
            return new TrackingProvider();
        }
    }
}

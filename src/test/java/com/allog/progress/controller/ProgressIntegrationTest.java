package com.allog.progress.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
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
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=${PROGRESS_TEST_DB_URL:jdbc:h2:mem:progress-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${PROGRESS_TEST_DB_USERNAME:sa}",
        "spring.datasource.password=${PROGRESS_TEST_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=${PROGRESS_TEST_DB_DRIVER:org.h2.Driver}",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ProgressIntegrationTest.TestConfig.class)
class ProgressIntegrationTest {

    private static final Clock QUERY_CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T10:00:00Z"),
            ZoneOffset.UTC
    );
    private static final Clock ACTIVATION_CLOCK = Clock.fixed(
            Instant.parse("2026-08-07T00:00:00Z"),
            ZoneOffset.UTC
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RoutineGroupActivationService activationService;

    private TransactionTemplate transaction;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void activeReturnsAuthoritativeFactsWithFourBoundedQueries() throws Exception {
        ActiveFixture fixture = activeFixture(GroupVisibility.PUBLIC);
        addApproved(fixture.ownerMemberId(), fixture.scheduleId(), "2026-08-07");
        addApproved(fixture.ownerMemberId(), fixture.scheduleId(), "2026-08-08");
        addSubmitted(fixture.ownerMemberId(), fixture.scheduleId(), "2026-08-11");
        addApproved(fixture.otherMemberId(), fixture.scheduleId(), "2026-08-07");
        addApproved(fixture.otherMemberId(), fixture.scheduleId(), "2026-08-08");
        addApproved(fixture.otherMemberId(), fixture.scheduleId(), "2026-08-09");
        statistics.clear();

        mockMvc.perform(authenticatedGet(fixture.groupId(), fixture.ownerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(jsonPath("$.participationStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.personal.*", hasSize(10)))
                .andExpect(jsonPath("$.personal.todayScheduled").value(true))
                .andExpect(jsonPath("$.personal.todayCompleted").value(false))
                .andExpect(jsonPath("$.personal.todayVerificationPending").value(true))
                .andExpect(jsonPath("$.personal.completedCount").value(2))
                .andExpect(jsonPath("$.personal.requiredCompletionCount").value(3))
                .andExpect(jsonPath("$.personal.currentStreak").value(0))
                .andExpect(jsonPath("$.personal.previousBestStreak").value(2))
                .andExpect(jsonPath("$.personal.remainingOpportunityCount").value(0))
                .andExpect(jsonPath("$.personal.pendingDecisionCount").value(1))
                .andExpect(jsonPath("$.personal.certificationDeadline")
                        .value("2026-08-11T14:00:00Z"))
                .andExpect(jsonPath("$.group.*", hasSize(6)))
                .andExpect(jsonPath("$.group.eligibleMemberCount").value(2))
                .andExpect(jsonPath("$.group.completedRequirementCount").value(5))
                .andExpect(jsonPath("$.group.totalRequiredCount").value(6))
                .andExpect(jsonPath("$.group.groupCompletionRate").value(5.0 / 6.0))
                .andExpect(jsonPath("$.group.pendingDecisionCount").value(1))
                .andExpect(jsonPath("$.group.goalAchievedMemberCount").value(1))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.groupMemberId").doesNotExist())
                .andExpect(jsonPath("$.routineScheduleId").doesNotExist())
                .andExpect(jsonPath("$.verificationId").doesNotExist());

        assertEquals(4, statistics.getPrepareStatementCount());
    }

    @ParameterizedTest
    @EnumSource(value = GroupMemberStatus.class, names = {"JOINED", "COMPLETED", "FAILED"})
    void visibleLifecycleReturnsStatusOnlyWithOneQuery(GroupMemberStatus memberStatus) throws Exception {
        MembershipFixture fixture = membershipFixture(
                GroupVisibility.PUBLIC,
                memberStatus,
                RoutineGroupStatus.ACTIVE
        );
        statistics.clear();

        mockMvc.perform(authenticatedGet(fixture.groupId(), fixture.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participationStatus").value(memberStatus.name()))
                .andExpect(jsonPath("$.personal").value(nullValue()))
                .andExpect(jsonPath("$.group").value(nullValue()));

        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @ParameterizedTest
    @EnumSource(value = GroupMemberStatus.class, names = {"LEFT", "REMOVED"})
    void hiddenLifecycleReturns404WithOneQuery(GroupMemberStatus memberStatus) throws Exception {
        MembershipFixture fixture = membershipFixture(
                GroupVisibility.PUBLIC,
                memberStatus,
                RoutineGroupStatus.ACTIVE
        );
        statistics.clear();

        mockMvc.perform(authenticatedGet(fixture.groupId(), fixture.userId()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void noMembershipReturns404WithOneQuery() throws Exception {
        CrossUserFixture fixture = crossUserFixture(GroupVisibility.PUBLIC);
        statistics.clear();

        mockMvc.perform(authenticatedGet(fixture.groupId(), fixture.outsiderUserId()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void ownPrivateMembershipIsReadable() throws Exception {
        MembershipFixture fixture = membershipFixture(
                GroupVisibility.PRIVATE,
                GroupMemberStatus.JOINED,
                RoutineGroupStatus.RECRUITING
        );

        mockMvc.perform(authenticatedGet(fixture.groupId(), fixture.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participationStatus").value("JOINED"));
    }

    @ParameterizedTest
    @EnumSource(GroupVisibility.class)
    void crossUserCannotReadPublicOrPrivateProgress(GroupVisibility visibility) throws Exception {
        CrossUserFixture fixture = crossUserFixture(visibility);

        mockMvc.perform(authenticatedGet(fixture.groupId(), fixture.outsiderUserId()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void activeWithoutScheduleReturnsStatusOnly500() throws Exception {
        MembershipFixture fixture = startedMembershipWithoutSchedule();

        mockMvc.perform(authenticatedGet(fixture.groupId(), fixture.userId()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
    }

    @Test
    void activeWithoutParticipationStartReturnsStatusOnly500() throws Exception {
        MembershipFixture fixture = membershipFixture(
                GroupVisibility.PUBLIC,
                GroupMemberStatus.ACTIVE,
                RoutineGroupStatus.ACTIVE
        );

        mockMvc.perform(authenticatedGet(fixture.groupId(), fixture.userId()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
    }

    private ActiveFixture activeFixture(GroupVisibility visibility) {
        ActiveFixture fixture = inTransaction(() -> {
            User owner = persistUser();
            User other = persistUser();
            RoutineGroup group = persistGroup(owner, visibility, RoutineGroupStatus.RECRUITING, 3);
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
            GroupMember ownerMember = persistMember(group, owner, GroupMemberRole.OWNER, GroupMemberStatus.JOINED);
            GroupMember otherMember = persistMember(group, other, GroupMemberRole.MEMBER, GroupMemberStatus.JOINED);
            entityManager.flush();
            return new ActiveFixture(
                    group.getId(),
                    schedule.getId(),
                    owner.getId(),
                    ownerMember.getId(),
                    otherMember.getId()
            );
        });
        activationService.activate(fixture.groupId(), ACTIVATION_CLOCK);
        return fixture;
    }

    private MembershipFixture membershipFixture(
            GroupVisibility visibility,
            GroupMemberStatus memberStatus,
            RoutineGroupStatus groupStatus
    ) {
        return inTransaction(() -> {
            User user = persistUser();
            RoutineGroup group = persistGroup(user, visibility, groupStatus, 1);
            persistMember(group, user, GroupMemberRole.OWNER, memberStatus);
            entityManager.flush();
            return new MembershipFixture(group.getId(), user.getId());
        });
    }

    private MembershipFixture startedMembershipWithoutSchedule() {
        return inTransaction(() -> {
            User user = persistUser();
            RoutineGroup group = persistGroup(user, GroupVisibility.PUBLIC, RoutineGroupStatus.ACTIVE, 1);
            GroupMember member = persistMember(
                    group,
                    user,
                    GroupMemberRole.OWNER,
                    GroupMemberStatus.JOINED
            );
            member.startParticipation(Instant.parse("2026-08-07T00:00:00Z"));
            entityManager.flush();
            return new MembershipFixture(group.getId(), user.getId());
        });
    }

    private CrossUserFixture crossUserFixture(GroupVisibility visibility) {
        return inTransaction(() -> {
            User owner = persistUser();
            User outsider = persistUser();
            RoutineGroup group = persistGroup(owner, visibility, RoutineGroupStatus.RECRUITING, 1);
            persistMember(group, owner, GroupMemberRole.OWNER, GroupMemberStatus.JOINED);
            entityManager.flush();
            return new CrossUserFixture(group.getId(), outsider.getId());
        });
    }

    private void addApproved(Long memberId, Long scheduleId, String date) {
        addVerification(memberId, scheduleId, date, true);
    }

    private void addSubmitted(Long memberId, Long scheduleId, String date) {
        addVerification(memberId, scheduleId, date, false);
    }

    private void addVerification(Long memberId, Long scheduleId, String date, boolean approve) {
        inTransaction(() -> {
            GroupMember member = entityManager.find(GroupMember.class, memberId);
            RoutineSchedule schedule = entityManager.find(RoutineSchedule.class, scheduleId);
            Verification verification = Verification.create(member, schedule, LocalDate.parse(date));
            verification.submit(QUERY_CLOCK);
            if (approve) {
                verification.startProcessing();
                verification.approve(QUERY_CLOCK);
            }
            entityManager.persist(verification);
            entityManager.flush();
            return null;
        });
    }

    private User persistUser() {
        User user = User.create();
        entityManager.persist(user);
        return user;
    }

    private RoutineGroup persistGroup(
            User owner,
            GroupVisibility visibility,
            RoutineGroupStatus status,
            int requiredCompletionCount
    ) {
        RoutineDefinition definition = new RoutineDefinition("물 마시기", null);
        entityManager.persist(definition);
        RoutineGroup group = new RoutineGroup(
                definition,
                owner,
                "아침 물 마시기",
                visibility,
                status,
                10,
                requiredCompletionCount
        );
        entityManager.persist(group);
        return group;
    }

    private GroupMember persistMember(
            RoutineGroup group,
            User user,
            GroupMemberRole role,
            GroupMemberStatus status
    ) {
        GroupMember member = new GroupMember(
                group,
                user,
                role,
                status,
                Instant.parse("2026-08-01T09:00:00Z")
        );
        entityManager.persist(member);
        return member;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedGet(
            Long groupId,
            Long userId
    ) {
        return get("/api/v1/me/groups/{groupId}/progress", groupId)
                .with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                        new AllogPrincipal(userId)
                )));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private record ActiveFixture(
            Long groupId,
            Long scheduleId,
            Long ownerUserId,
            Long ownerMemberId,
            Long otherMemberId
    ) {
    }

    private record MembershipFixture(Long groupId, Long userId) {
    }

    private record CrossUserFixture(Long groupId, Long outsiderUserId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        Clock progressTestClock() {
            return QUERY_CLOCK;
        }
    }
}

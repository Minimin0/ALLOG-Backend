package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.AllogAuthenticationToken;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=${MY_GROUPS_TEST_DB_URL:jdbc:h2:mem:my-groups;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${MY_GROUPS_TEST_DB_USERNAME:sa}",
        "spring.datasource.password=${MY_GROUPS_TEST_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=${MY_GROUPS_TEST_DB_DRIVER:org.h2.Driver}",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyGroupIntegrationTest {

    private static final String ENDPOINT = "/api/v1/me/groups";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void returnsOnlyPrincipalVisibleMembershipsWithStableSortAndOneSelect() throws Exception {
        Fixture fixture = inTransaction(() -> {
            User currentUser = persistUser();
            User otherUser = persistUser();
            addMembership(currentUser, "완료 그룹", GroupVisibility.PUBLIC,
                    RoutineGroupStatus.COMPLETED, GroupMemberStatus.COMPLETED, "2026-08-10T10:00:00Z");
            addMembership(currentUser, "실패 그룹", GroupVisibility.PUBLIC,
                    RoutineGroupStatus.COMPLETED, GroupMemberStatus.FAILED, "2026-08-10T10:00:00Z");
            addMembership(currentUser, "비공개 활성 그룹", GroupVisibility.PRIVATE,
                    RoutineGroupStatus.ACTIVE, GroupMemberStatus.ACTIVE, "2026-08-10T11:00:00Z");
            addMembership(currentUser, "참여 대기 그룹", GroupVisibility.PUBLIC,
                    RoutineGroupStatus.RECRUITING, GroupMemberStatus.JOINED, "2026-08-10T12:00:00Z");
            addMembership(currentUser, "탈퇴 그룹", GroupVisibility.PUBLIC,
                    RoutineGroupStatus.ACTIVE, GroupMemberStatus.LEFT, "2026-08-10T13:00:00Z");
            addMembership(currentUser, "제거 그룹", GroupVisibility.PRIVATE,
                    RoutineGroupStatus.ACTIVE, GroupMemberStatus.REMOVED, "2026-08-10T14:00:00Z");
            addMembership(otherUser, "타인의 공개 그룹", GroupVisibility.PUBLIC,
                    RoutineGroupStatus.ACTIVE, GroupMemberStatus.ACTIVE, "2026-08-10T15:00:00Z");
            addMembership(otherUser, "타인의 비공개 그룹", GroupVisibility.PRIVATE,
                    RoutineGroupStatus.ACTIVE, GroupMemberStatus.ACTIVE, "2026-08-10T16:00:00Z");
            entityManager.flush();
            return new Fixture(currentUser.getId(), otherUser.getId());
        });
        statistics.clear();

        mockMvc.perform(authenticatedGet(fixture.currentUserId())
                        .queryParam("userId", fixture.otherUserId().toString())
                        .queryParam("status", "LEFT")
                        .queryParam("sort", "myStatus,asc")
                        .header("X-User-Id", fixture.otherUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[*].groupName", contains(
                        "참여 대기 그룹",
                        "비공개 활성 그룹",
                        "실패 그룹",
                        "완료 그룹"
                )))
                .andExpect(jsonPath("$.items[*].myStatus", contains(
                        "JOINED", "ACTIVE", "FAILED", "COMPLETED"
                )))
                .andExpect(jsonPath("$.items[1].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.items[0].*", hasSize(7)))
                .andExpect(jsonPath("$.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.items[0].groupMemberId").doesNotExist())
                .andExpect(jsonPath("$.items[0].joinedAt").doesNotExist())
                .andExpect(jsonPath("$.items[0].participationStartedAt").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.totalPages").doesNotExist());

        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void returnsSliceHasNextWithoutCountQuery() throws Exception {
        Long userId = inTransaction(() -> {
            User user = persistUser();
            addMembership(user, "첫째", GroupVisibility.PUBLIC,
                    RoutineGroupStatus.ACTIVE, GroupMemberStatus.ACTIVE, "2026-08-10T10:00:00Z");
            addMembership(user, "둘째", GroupVisibility.PUBLIC,
                    RoutineGroupStatus.ACTIVE, GroupMemberStatus.ACTIVE, "2026-08-10T11:00:00Z");
            addMembership(user, "첫째", GroupVisibility.PUBLIC,
                    RoutineGroupStatus.ACTIVE, GroupMemberStatus.ACTIVE, "2026-08-10T12:00:00Z");
            entityManager.flush();
            return user.getId();
        });

        statistics.clear();
        mockMvc.perform(authenticatedGet(userId).queryParam("page", "0").queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].groupName", contains("첫째", "둘째")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
        assertEquals(1, statistics.getPrepareStatementCount());

        statistics.clear();
        mockMvc.perform(authenticatedGet(userId).queryParam("page", "1").queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].groupName", contains("첫째")))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));
        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void returnsEmptySliceWhenOnlyHiddenMembershipsExist() throws Exception {
        Long userId = inTransaction(() -> {
            User user = persistUser();
            addMembership(user, "탈퇴", GroupVisibility.PUBLIC,
                    RoutineGroupStatus.ACTIVE, GroupMemberStatus.LEFT, "2026-08-10T10:00:00Z");
            addMembership(user, "제거", GroupVisibility.PRIVATE,
                    RoutineGroupStatus.ACTIVE, GroupMemberStatus.REMOVED, "2026-08-10T11:00:00Z");
            entityManager.flush();
            return user.getId();
        });
        statistics.clear();

        mockMvc.perform(authenticatedGet(userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(false));

        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @ParameterizedTest
    @EnumSource(value = GroupMemberStatus.class, names = {"JOINED", "ACTIVE", "COMPLETED", "FAILED"})
    void returnsPrivateDetailForEveryVisibleMembershipWithNullableSchedule(GroupMemberStatus status) throws Exception {
        DetailFixture fixture = detailFixture(GroupVisibility.PRIVATE, status, null, Set.of());
        statistics.clear();

        mockMvc.perform(authenticatedDetailGet(fixture.userId(), fixture.groupId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.group.*", hasSize(7)))
                .andExpect(jsonPath("$.group.groupId").value(fixture.groupId()))
                .andExpect(jsonPath("$.group.visibility").value("PRIVATE"))
                // The fixture holds one membership, and every status this runs with is a visible one.
                .andExpect(jsonPath("$.group.currentMembers").value(1))
                .andExpect(jsonPath("$.routine.*", hasSize(2)))
                .andExpect(jsonPath("$.routine.description", nullValue()))
                .andExpect(jsonPath("$.schedule", nullValue()))
                .andExpect(jsonPath("$.membership.*", hasSize(2)))
                .andExpect(jsonPath("$.membership.myStatus").value(status.name()))
                .andExpect(jsonPath("$.membership.joinedAt").doesNotExist())
                .andExpect(jsonPath("$.membership.participationStartedAt").doesNotExist())
                .andExpect(jsonPath("$.membership.groupMemberId").doesNotExist())
                .andExpect(jsonPath("$.createdByUserId").doesNotExist())
                .andExpect(jsonPath("$.progress").doesNotExist())
                .andExpect(jsonPath("$.aiCoach").doesNotExist())
                .andExpect(jsonPath("$.memberCount").doesNotExist());

        // Membership, schedule, and the visible-member count. The count is its own statement on
        // purpose: joining it onto the membership read would fan the row out per member.
        assertEquals(3, statistics.getPrepareStatementCount());
    }

    @ParameterizedTest
    @EnumSource(value = GroupMemberStatus.class, names = {"LEFT", "REMOVED"})
    void hidesFormerMembershipDetail(GroupMemberStatus status) throws Exception {
        DetailFixture fixture = detailFixture(GroupVisibility.PUBLIC, status, ScheduleType.DAILY, Set.of());
        statistics.clear();

        mockMvc.perform(authenticatedDetailGet(fixture.userId(), fixture.groupId()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @ParameterizedTest
    @EnumSource(GroupVisibility.class)
    void hidesOtherUsersGroupRegardlessOfVisibilityAndIgnoresSpoofedIdentity(GroupVisibility visibility)
            throws Exception {
        CrossUserFixture fixture = crossUserFixture(visibility);
        statistics.clear();

        mockMvc.perform(authenticatedDetailGet(fixture.currentUserId(), fixture.groupId())
                        .queryParam("userId", fixture.otherUserId().toString())
                        .header("X-User-Id", fixture.otherUserId()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void nonexistentGroupReturnsSameStatusOnly404() throws Exception {
        Long userId = inTransaction(() -> {
            User user = persistUser();
            entityManager.flush();
            return user.getId();
        });
        statistics.clear();

        mockMvc.perform(authenticatedDetailGet(userId, Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test
    void returnsDailyScheduleWithEmptySpecificDaysInThreeQueries() throws Exception {
        DetailFixture fixture = detailFixture(
                GroupVisibility.PUBLIC,
                GroupMemberStatus.ACTIVE,
                ScheduleType.DAILY,
                Set.of()
        );
        statistics.clear();

        mockMvc.perform(authenticatedDetailGet(fixture.userId(), fixture.groupId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.*", hasSize(6)))
                .andExpect(jsonPath("$.schedule.scheduleType").value("DAILY"))
                .andExpect(jsonPath("$.schedule.startDate").value("2026-08-10"))
                .andExpect(jsonPath("$.schedule.endDate").value("2026-08-24"))
                .andExpect(jsonPath("$.schedule.deadlineTime").value("22:00:00"))
                .andExpect(jsonPath("$.schedule.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.schedule.specificDays").isEmpty());

        assertEquals(3, statistics.getPrepareStatementCount());
    }

    @Test
    void returnsSpecificDaysInStableWeekdayOrderInThreeQueries() throws Exception {
        DetailFixture fixture = detailFixture(
                GroupVisibility.PUBLIC,
                GroupMemberStatus.ACTIVE,
                ScheduleType.SPECIFIC_DAYS,
                Set.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        );
        statistics.clear();

        mockMvc.perform(authenticatedDetailGet(fixture.userId(), fixture.groupId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.scheduleType").value("SPECIFIC_DAYS"))
                .andExpect(jsonPath("$.schedule.specificDays", contains(
                        "MONDAY", "WEDNESDAY", "FRIDAY"
                )));

        assertEquals(3, statistics.getPrepareStatementCount());
    }

    private User persistUser() {
        User user = User.create();
        entityManager.persist(user);
        return user;
    }

    private GroupMember addMembership(
            User user,
            String groupName,
            GroupVisibility visibility,
            RoutineGroupStatus groupStatus,
            GroupMemberStatus memberStatus,
            String joinedAt
    ) {
        RoutineDefinition routine = new RoutineDefinition(groupName + " 루틴", null);
        entityManager.persist(routine);
        RoutineGroup group = new RoutineGroup(
                routine,
                user,
                groupName,
                visibility,
                groupStatus,
                10,
                5
        );
        entityManager.persist(group);
        GroupMember membership = new GroupMember(
                group,
                user,
                GroupMemberRole.MEMBER,
                memberStatus,
                Instant.parse(joinedAt)
        );
        entityManager.persist(membership);
        return membership;
    }

    private MockHttpServletRequestBuilder authenticatedGet(Long userId) {
        return get(ENDPOINT).with(authentication(AllogAuthenticationToken.authenticated(
                new AllogPrincipal(userId)
        )));
    }

    private MockHttpServletRequestBuilder authenticatedDetailGet(Long userId, Long groupId) {
        return get(ENDPOINT + "/{groupId}", groupId).with(authentication(
                AllogAuthenticationToken.authenticated(new AllogPrincipal(userId))
        ));
    }

    private DetailFixture detailFixture(
            GroupVisibility visibility,
            GroupMemberStatus status,
            ScheduleType scheduleType,
            Set<DayOfWeek> specificDays
    ) {
        return inTransaction(() -> {
            User user = persistUser();
            GroupMember membership = addMembership(
                    user,
                    status + " 상세 그룹",
                    visibility,
                    RoutineGroupStatus.ACTIVE,
                    status,
                    "2026-08-10T10:00:00Z"
            );
            if (scheduleType != null) {
                entityManager.persist(new RoutineSchedule(
                        membership.getRoutineGroup(),
                        scheduleType,
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 8, 24),
                        LocalTime.of(22, 0),
                        "Asia/Seoul",
                        specificDays
                ));
            }
            entityManager.flush();
            return new DetailFixture(user.getId(), membership.getRoutineGroup().getId());
        });
    }

    private CrossUserFixture crossUserFixture(GroupVisibility visibility) {
        return inTransaction(() -> {
            User currentUser = persistUser();
            User otherUser = persistUser();
            GroupMember membership = addMembership(
                    otherUser,
                    visibility + " 타인 그룹",
                    visibility,
                    RoutineGroupStatus.ACTIVE,
                    GroupMemberStatus.ACTIVE,
                    "2026-08-10T10:00:00Z"
            );
            entityManager.flush();
            return new CrossUserFixture(
                    currentUser.getId(),
                    otherUser.getId(),
                    membership.getRoutineGroup().getId()
            );
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private record Fixture(Long currentUserId, Long otherUserId) {
    }

    private record DetailFixture(Long userId, Long groupId) {
    }

    private record CrossUserFixture(Long currentUserId, Long otherUserId, Long groupId) {
    }
}

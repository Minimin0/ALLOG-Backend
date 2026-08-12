package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private User persistUser() {
        User user = User.create();
        entityManager.persist(user);
        return user;
    }

    private void addMembership(
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
        entityManager.persist(new GroupMember(
                group,
                user,
                GroupMemberRole.MEMBER,
                memberStatus,
                Instant.parse(joinedAt)
        ));
    }

    private MockHttpServletRequestBuilder authenticatedGet(Long userId) {
        return get(ENDPOINT).with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(userId)
        )));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private record Fixture(Long currentUserId, Long otherUserId) {
    }
}

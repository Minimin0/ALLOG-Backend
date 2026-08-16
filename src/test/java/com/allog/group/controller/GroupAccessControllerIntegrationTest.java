package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.repository.RoutineGroupInviteRepository;
import com.allog.heart.domain.HeartWallet;
import com.allog.heart.repository.HeartWalletRepository;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.function.Supplier;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GroupAccessControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RoutineGroupInviteRepository inviteRepository;
    @Autowired
    private HeartWalletRepository walletRepository;

    private TransactionTemplate transaction;

    @BeforeEach
    void clearGroupAccessTables() {
        transaction = new TransactionTemplate(transactionManager);
        inTransaction(() -> {
            entityManager.createQuery("delete from RoutineGroupInvite").executeUpdate();
            entityManager.createQuery("delete from HeartLedgerEntry").executeUpdate();
            entityManager.createQuery("delete from HeartWallet").executeUpdate();
            entityManager.createQuery("delete from RoutineSchedule").executeUpdate();
            entityManager.createQuery("delete from GroupMember").executeUpdate();
            entityManager.createQuery("delete from RoutineGroup").executeUpdate();
            return null;
        });
    }

    @Test
    void exploreExposesOnlyPublicRecruitingGroups() throws Exception {
        Fixture visible = fixture(GroupVisibility.PUBLIC, RoutineGroupStatus.RECRUITING, 3);
        fixture(GroupVisibility.PRIVATE, RoutineGroupStatus.RECRUITING, 3);
        fixture(GroupVisibility.PUBLIC, RoutineGroupStatus.FULL, 3);

        mockMvc.perform(get("/api/v1/groups")).andExpect(status().isUnauthorized());
        mockMvc.perform(authenticated(get("/api/v1/groups"), visible.ownerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].groupId").value(visible.groupId()))
                .andExpect(jsonPath("$.items[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.items[0].status").value("RECRUITING"))
                .andExpect(jsonPath("$.items[0].currentMembers").value(1));
    }

    @Test
    void privateGroupRequiresOwnerIssuedCodeAndUsesExistingHeartJoin() throws Exception {
        Fixture fixture = fixture(GroupVisibility.PRIVATE, RoutineGroupStatus.RECRUITING, 2);

        mockMvc.perform(authenticated(post("/api/v1/groups/{groupId}/join", fixture.groupId()), fixture.guestUserId()))
                .andExpect(status().isConflict());
        assertEquals(3, balance(fixture.guestUserId()));
        mockMvc.perform(authenticated(get("/api/v1/me/groups/{groupId}", fixture.groupId()), fixture.guestUserId()))
                .andExpect(status().isNotFound());

        mockMvc.perform(authenticated(post("/api/v1/me/groups/{groupId}/invite", fixture.groupId()), fixture.guestUserId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(authenticated(post("/api/v1/me/groups/{groupId}/invite", fixture.groupId()), fixture.ownerUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty());

        String code = inTransaction(() -> inviteRepository.findByRoutineGroup_Id(fixture.groupId()).orElseThrow().getCode());
        mockMvc.perform(authenticated(post("/api/v1/groups/join-by-invite")
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\"}"), fixture.guestUserId()))
                .andExpect(status().isNoContent());

        inTransaction(() -> {
            assertEquals(2, balance(fixture.guestUserId()));
            assertEquals(RoutineGroupStatus.ACTIVE,
                    entityManager.find(RoutineGroup.class, fixture.groupId()).getStatus());
            assertEquals(2L, entityManager.createQuery(
                            "select count(m) from GroupMember m where m.routineGroup.id = :groupId", Long.class)
                    .setParameter("groupId", fixture.groupId())
                    .getSingleResult());
            return null;
        });
    }

    private Fixture fixture(GroupVisibility visibility, RoutineGroupStatus statusValue, int maxMembers) {
        return inTransaction(() -> {
            User owner = User.create();
            User guest = User.create();
            RoutineDefinition definition = new RoutineDefinition("routine", "description");
            entityManager.persist(owner);
            entityManager.persist(guest);
            entityManager.persist(HeartWallet.openWith(guest, 3));
            entityManager.persist(definition);
            RoutineGroup group = new RoutineGroup(
                    definition, owner, "group", visibility, statusValue, maxMembers, 1);
            entityManager.persist(group);
            entityManager.persist(new RoutineSchedule(
                    group,
                    ScheduleType.DAILY,
                    LocalDate.now().minusDays(1),
                    LocalDate.now().plusDays(10),
                    LocalTime.of(23, 0),
                    "UTC",
                    Set.of()
            ));
            entityManager.persist(new GroupMember(
                    group, owner, GroupMemberRole.OWNER, GroupMemberStatus.JOINED, Instant.now()));
            entityManager.flush();
            return new Fixture(group.getId(), owner.getId(), guest.getId());
        });
    }

    private int balance(Long userId) {
        return walletRepository.findByUser_Id(userId).orElseThrow().getBalance();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private record Fixture(Long groupId, Long ownerUserId, Long guestUserId) {
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, Long userId) {
        return request.with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(userId))));
    }
}

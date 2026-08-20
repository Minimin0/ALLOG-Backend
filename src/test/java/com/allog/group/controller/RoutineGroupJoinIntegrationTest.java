package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.AllogAuthenticationToken;
import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.heart.domain.HeartWallet;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.service.RoutineGroupActivationService;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import com.allog.verification.template.VerificationTemplateCatalog;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=${GROUP_JOIN_TEST_DB_URL:jdbc:h2:mem:group-join;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${GROUP_JOIN_TEST_DB_USERNAME:sa}",
        "spring.datasource.password=${GROUP_JOIN_TEST_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=${GROUP_JOIN_TEST_DB_DRIVER:org.h2.Driver}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoutineGroupJoinIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RoutineGroupActivationService activationService;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        inTransaction(() -> {
            entityManager.createQuery("select s from RoutineSchedule s", RoutineSchedule.class)
                    .getResultList().forEach(entityManager::remove);
            members().forEach(entityManager::remove);
            groups().forEach(entityManager::remove);
            entityManager.flush();
            return null;
        });
    }

    @Test
    void joinsRecruitingGroupAsMemberWithoutFillingIt() throws Exception {
        Fixture fixture = fixture(3, RoutineGroupStatus.RECRUITING, true);

        mockMvc.perform(join(fixture.groupId(), fixture.joinerId()))
                .andExpect(status().isNoContent());

        inTransaction(() -> {
            GroupMember joined = memberOf(fixture.groupId(), fixture.joinerId());
            assertAll(
                    () -> assertEquals(GroupMemberRole.MEMBER, joined.getRole()),
                    () -> assertEquals(GroupMemberStatus.JOINED, joined.getStatus()),
                    // Owner + joiner = 2 of 3, so the group keeps recruiting.
                    () -> assertEquals(RoutineGroupStatus.RECRUITING, group(fixture.groupId()).getStatus()),
                    () -> assertEquals(2, joinedCount(fixture.groupId()))
            );
            return null;
        });
    }

    /** The join that fills the room also starts it, in the same transaction. */
    @Test
    void startsTheGroupWhenTheOwnerAndJoinerFillCapacity() throws Exception {
        Fixture fixture = fixture(2, RoutineGroupStatus.RECRUITING, true);

        mockMvc.perform(join(fixture.groupId(), fixture.joinerId()))
                .andExpect(status().isNoContent());

        inTransaction(() -> {
            assertAll(
                    // The owner occupies one of the two slots.
                    () -> assertEquals(RoutineGroupStatus.ACTIVE, group(fixture.groupId()).getStatus()),
                    () -> assertEquals(0, joinedCount(fixture.groupId())),
                    () -> assertEquals(2, activeCount(fixture.groupId()))
            );
            return null;
        });
    }

    @Test
    void rejectsJoiningAGroupThatIsAlreadyFull() throws Exception {
        Fixture fixture = fixture(1, RoutineGroupStatus.RECRUITING, true);

        // The client shows a different message per reason, so the reason travels in the body.
        // Without this the switch could map every rejection to the same code and stay green.
        mockMvc.perform(join(fixture.groupId(), fixture.joinerId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_FULL"));

        inTransaction(() -> {
            assertEquals(1, joinedCount(fixture.groupId()));
            return null;
        });
    }

    @Test
    void rejectsDuplicateJoinByTheSameUser() throws Exception {
        Fixture fixture = fixture(5, RoutineGroupStatus.RECRUITING, true);

        mockMvc.perform(join(fixture.groupId(), fixture.joinerId())).andExpect(status().isNoContent());
        mockMvc.perform(join(fixture.groupId(), fixture.joinerId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_JOINED"));

        inTransaction(() -> {
            assertEquals(2, joinedCount(fixture.groupId()));
            return null;
        });
    }

    @Test
    void rejectsJoiningAGroupThatIsNoLongerRecruiting() throws Exception {
        Fixture fixture = fixture(5, RoutineGroupStatus.ACTIVE, true);

        mockMvc.perform(join(fixture.groupId(), fixture.joinerId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_JOINABLE"));

        inTransaction(() -> {
            assertEquals(1, joinedCount(fixture.groupId()));
            return null;
        });
    }

    @Test
    void rejectsUnknownGroup() throws Exception {
        Fixture fixture = fixture(5, RoutineGroupStatus.RECRUITING, true);

        // The same handler answers 404, so this one carries a body too. Pinning it here keeps a
        // client that reads the body from meeting an empty 404 it did not expect.
        mockMvc.perform(join(fixture.groupId() + 9999, fixture.joinerId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
    }

    @Test
    void rejectsUnauthenticatedJoin() throws Exception {
        Fixture fixture = fixture(5, RoutineGroupStatus.RECRUITING, true);

        mockMvc.perform(post("/api/v1/groups/{groupId}/join", fixture.groupId()))
                .andExpect(status().isUnauthorized());

        inTransaction(() -> {
            assertEquals(1, joinedCount(fixture.groupId()));
            return null;
        });
    }

    @Test
    void leavesVerificationBindingAndScheduleUntouchedWhenTheGroupStarts() throws Exception {
        Fixture fixture = fixture(2, RoutineGroupStatus.RECRUITING, true);

        mockMvc.perform(join(fixture.groupId(), fixture.joinerId()))
                .andExpect(status().isNoContent());

        inTransaction(() -> {
            RoutineGroup group = group(fixture.groupId());
            assertAll(
                    () -> assertEquals(RoutineGroupStatus.ACTIVE, group.getStatus()),
                    () -> assertTrue(group.hasVerificationBinding()),
                    () -> assertEquals(
                            VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1,
                            group.getVerificationCriteriaReference()
                    ),
                    () -> assertEquals(fixture.ownerId(), group.getCreatedBy().getId()),
                    () -> assertEquals(fixture.routineDefinitionId(), group.getRoutineDefinition().getId())
            );
            return null;
        });

        // The join already started it, so no separate activation step is needed - and the members
        // it started are the ones now running.
        inTransaction(() -> {
            RoutineGroup group = group(fixture.groupId());
            assertAll(
                    () -> assertEquals(RoutineGroupStatus.ACTIVE, group.getStatus()),
                    () -> assertTrue(group.hasVerificationBinding()),
                    () -> assertEquals(2, entityManager.createQuery(
                            "select count(m) from GroupMember m where m.routineGroup.id = :id and m.status = :s",
                            Long.class
                    ).setParameter("id", fixture.groupId()).setParameter("s", GroupMemberStatus.ACTIVE)
                            .getSingleResult())
            );
            return null;
        });
    }

    @Test
    void recordOnlyGroupKeepsItsNullBindingAfterJoin() throws Exception {
        Fixture fixture = fixture(5, RoutineGroupStatus.RECRUITING, false);

        mockMvc.perform(join(fixture.groupId(), fixture.joinerId()))
                .andExpect(status().isNoContent());

        inTransaction(() -> {
            assertFalse(group(fixture.groupId()).hasVerificationBinding());
            return null;
        });
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder join(
            Long groupId,
            Long userId
    ) {
        return post("/api/v1/groups/{groupId}/join", groupId)
                .with(authentication(AllogAuthenticationToken.authenticated(new AllogPrincipal(userId))));
    }

    private Fixture fixture(int maxMembers, RoutineGroupStatus status, boolean verificationBound) {
        return inTransaction(() -> {
            User owner = User.create();
            User joiner = User.create();
            entityManager.persist(owner);
            entityManager.persist(joiner);
            entityManager.persist(HeartWallet.openWith(joiner, 3));
            RoutineDefinition definition = new RoutineDefinition("아침 식사", "매일 아침 식사를 기록합니다");
            entityManager.persist(definition);

            RoutineGroup group = verificationBound
                    ? new RoutineGroup(definition, owner, "그룹", GroupVisibility.PUBLIC, status, maxMembers, 1,
                    new VerificationTemplateCatalog().requireTemplate(VerificationTemplateCatalog.MEAL_PHOTO_RECORD))
                    : new RoutineGroup(definition, owner, "그룹", GroupVisibility.PUBLIC, status, maxMembers, 1);
            entityManager.persist(group);
            entityManager.persist(new GroupMember(
                    group,
                    owner,
                    GroupMemberRole.OWNER,
                    GroupMemberStatus.JOINED,
                    Instant.parse("2026-09-01T00:00:00Z")
            ));
            entityManager.persist(new RoutineSchedule(
                    group,
                    ScheduleType.DAILY,
                    LocalDate.parse("2026-09-01"),
                    LocalDate.parse("2026-09-30"),
                    LocalTime.parse("22:00:00"),
                    "Asia/Seoul",
                    Set.<DayOfWeek>of()
            ));
            entityManager.flush();
            return new Fixture(group.getId(), owner.getId(), joiner.getId(), definition.getId());
        });
    }

    private RoutineGroup group(Long groupId) {
        return entityManager.find(RoutineGroup.class, groupId);
    }

    private GroupMember memberOf(Long groupId, Long userId) {
        return entityManager.createQuery(
                        "select m from GroupMember m where m.routineGroup.id = :g and m.user.id = :u",
                        GroupMember.class)
                .setParameter("g", groupId)
                .setParameter("u", userId)
                .getSingleResult();
    }

    private long activeCount(Long groupId) {
        return entityManager.createQuery(
                        "select count(m) from GroupMember m where m.routineGroup.id = :g and m.status = :s",
                        Long.class)
                .setParameter("g", groupId)
                .setParameter("s", GroupMemberStatus.ACTIVE)
                .getSingleResult();
    }

    private long joinedCount(Long groupId) {
        return entityManager.createQuery(
                        "select count(m) from GroupMember m where m.routineGroup.id = :g and m.status = :s",
                        Long.class)
                .setParameter("g", groupId)
                .setParameter("s", GroupMemberStatus.JOINED)
                .getSingleResult();
    }

    private List<GroupMember> members() {
        return entityManager.createQuery("select m from GroupMember m", GroupMember.class).getResultList();
    }

    private List<RoutineGroup> groups() {
        return entityManager.createQuery("select g from RoutineGroup g", RoutineGroup.class).getResultList();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private record Fixture(Long groupId, Long ownerId, Long joinerId, Long routineDefinitionId) {
    }
}

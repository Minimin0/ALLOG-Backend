package com.allog.group.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=${GROUP_CREATION_TEST_DB_URL:jdbc:h2:mem:group-creation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${GROUP_CREATION_TEST_DB_USERNAME:sa}",
        "spring.datasource.password=${GROUP_CREATION_TEST_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=${GROUP_CREATION_TEST_DB_DRIVER:org.h2.Driver}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoutineGroupCreationIntegrationTest {

    private static final String ENDPOINT = "/api/v1/me/groups";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private VerificationTemplateCatalog templateCatalog;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        // MockMvc calls commit outside the test transaction, so rows survive between methods.
        inTransaction(() -> {
            schedules().forEach(entityManager::remove);
            members().forEach(entityManager::remove);
            groups().forEach(entityManager::remove);
            entityManager.flush();
            return null;
        });
    }

    @Test
    void createsRecordOnlyGroupWithOwnerMembershipAndScheduleAndNoVerificationBinding() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(authenticatedPost(fixture.userId(), body(fixture.routineDefinitionId(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").isNumber());

        inTransaction(() -> {
            RoutineGroup group = onlyGroup();
            GroupMember owner = onlyMember();
            RoutineSchedule schedule = onlySchedule();
            assertAll(
                    () -> assertEquals("아침 식사 기록", group.getName()),
                    () -> assertEquals(RoutineGroupStatus.RECRUITING, group.getStatus()),
                    () -> assertTrue(!group.hasVerificationBinding(), "record-only group must have no binding"),
                    () -> assertNull(group.getVerificationTemplateKey()),
                    () -> assertNull(group.getVerificationCriteriaReference()),
                    () -> assertEquals(GroupMemberRole.OWNER, owner.getRole()),
                    () -> assertEquals(GroupMemberStatus.JOINED, owner.getStatus()),
                    () -> assertEquals(fixture.userId(), owner.getUser().getId()),
                    () -> assertEquals(ScheduleType.SPECIFIC_DAYS, schedule.getScheduleType()),
                    () -> assertEquals(java.util.Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), schedule.getSpecificDays()),
                    () -> assertEquals("Asia/Seoul", schedule.getTimezone()),
                    () -> assertEquals(group.getId(), schedule.getRoutineGroup().getId())
            );
            return null;
        });
    }

    @Test
    void createsVerificationBoundGroupPinningTheExactCriteriaTheTemplateDeclares() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(authenticatedPost(fixture.userId(), body(fixture.routineDefinitionId(), "MEAL_PHOTO_RECORD")))
                .andExpect(status().isCreated());

        inTransaction(() -> {
            RoutineGroup group = onlyGroup();
            assertAll(
                    () -> assertTrue(group.hasVerificationBinding()),
                    () -> assertEquals(
                            VerificationTemplateCatalog.MEAL_PHOTO_RECORD,
                            group.getVerificationTemplateKey()
                    ),
                    // The exact pinned reference, not a latest or highest version lookup.
                    () -> assertEquals(
                            VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1,
                            group.getVerificationCriteriaReference()
                    ),
                    () -> assertEquals(
                            "meal-photo-record@1",
                            group.getVerificationCriteriaReference().storageValue()
                    ),
                    // The submit path resolves the binding through this exact-pair lookup, so a group
                    // created here cannot fail later with a missing or mismatched criteria.
                    () -> assertEquals(
                            VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1,
                            templateCatalog.resolve(
                                    group.getVerificationTemplateKey(),
                                    group.getVerificationCriteriaReference()
                            ).reference()
                    )
            );
            return null;
        });
    }

    @Test
    void acceptsLowercaseTemplateKeyThroughTheValueObjectNormalisation() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(authenticatedPost(fixture.userId(), body(fixture.routineDefinitionId(), "meal_photo_record")))
                .andExpect(status().isCreated());

        inTransaction(() -> {
            assertEquals(VerificationTemplateCatalog.MEAL_PHOTO_RECORD, onlyGroup().getVerificationTemplateKey());
            return null;
        });
    }

    @Test
    void rejectsUnapprovedTemplateWithoutDowngradingToRecordOnly() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(authenticatedPost(fixture.userId(), body(fixture.routineDefinitionId(), "UNKNOWN_TEMPLATE")))
                .andExpect(status().isBadRequest());

        assertNothingPersisted();
    }

    @Test
    void rejectsMalformedTemplateKey() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(authenticatedPost(fixture.userId(), body(fixture.routineDefinitionId(), "not a key!")))
                .andExpect(status().isBadRequest());

        assertNothingPersisted();
    }

    @Test
    void rejectsUnknownRoutineDefinition() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(authenticatedPost(fixture.userId(), body(fixture.routineDefinitionId() + 9999, null)))
                .andExpect(status().isNotFound());

        assertNothingPersisted();
    }

    @Test
    void rollsBackTheWholeCreationWhenTheScheduleIsInvalid() throws Exception {
        Fixture fixture = fixture();
        String invalidSchedule = """
                {
                  "routineDefinitionId": %d,
                  "name": "아침 식사 기록",
                  "visibility": "PUBLIC",
                  "maxMembers": 5,
                  "requiredCompletionCount": 3,
                  "schedule": {
                    "scheduleType": "SPECIFIC_DAYS",
                    "startDate": "2026-09-30",
                    "endDate": "2026-09-01",
                    "deadlineTime": "22:00:00",
                    "timezone": "Asia/Seoul",
                    "specificDays": ["MONDAY"]
                  }
                }
                """.formatted(fixture.routineDefinitionId());

        mockMvc.perform(authenticatedPost(fixture.userId(), invalidSchedule))
                .andExpect(status().isBadRequest());

        // The group is saved before the schedule, so this proves the transaction actually rolled back.
        assertNothingPersisted();
    }

    @Test
    void rejectsSpecificDaysScheduleWithoutDays() throws Exception {
        Fixture fixture = fixture();
        String noDays = body(fixture.routineDefinitionId(), null).replace("[\"MONDAY\",\"WEDNESDAY\"]", "[]");

        mockMvc.perform(authenticatedPost(fixture.userId(), noDays))
                .andExpect(status().isBadRequest());

        assertNothingPersisted();
    }

    @Test
    void rejectsRequestBodyThatFailsTransportValidation() throws Exception {
        Fixture fixture = fixture();
        String blankName = body(fixture.routineDefinitionId(), null).replace("\"아침 식사 기록\"", "\"  \"");

        mockMvc.perform(authenticatedPost(fixture.userId(), blankName))
                .andExpect(status().isBadRequest());

        assertNothingPersisted();
    }

    @Test
    void rejectsUnauthenticatedCreation() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(fixture.routineDefinitionId(), null)))
                .andExpect(status().isUnauthorized());

        assertNothingPersisted();
    }

    private void assertNothingPersisted() {
        inTransaction(() -> {
            assertAll(
                    () -> assertEquals(List.of(), groups()),
                    () -> assertEquals(List.of(), members()),
                    () -> assertEquals(List.of(), schedules())
            );
            return null;
        });
    }

    private String body(Long routineDefinitionId, String verificationTemplateKey) {
        String key = verificationTemplateKey == null ? "null" : "\"" + verificationTemplateKey + "\"";
        return """
                {
                  "routineDefinitionId": %d,
                  "name": "아침 식사 기록",
                  "visibility": "PUBLIC",
                  "maxMembers": 5,
                  "requiredCompletionCount": 3,
                  "verificationTemplateKey": %s,
                  "schedule": {
                    "scheduleType": "SPECIFIC_DAYS",
                    "startDate": "2026-09-01",
                    "endDate": "2026-09-30",
                    "deadlineTime": "22:00:00",
                    "timezone": "Asia/Seoul",
                    "specificDays": ["MONDAY","WEDNESDAY"]
                  }
                }
                """.formatted(routineDefinitionId, key);
    }

    private MockHttpServletRequestBuilder authenticatedPost(Long userId, String body) {
        return post(ENDPOINT)
                .with(authentication(FirebaseBearerAuthenticationToken.authenticated(new AllogPrincipal(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Fixture fixture() {
        return inTransaction(() -> {
            User user = User.create();
            entityManager.persist(user);
            RoutineDefinition definition = new RoutineDefinition("아침 식사", "매일 아침 식사를 기록합니다");
            entityManager.persist(definition);
            entityManager.flush();
            return new Fixture(user.getId(), definition.getId());
        });
    }

    private RoutineGroup onlyGroup() {
        List<RoutineGroup> found = groups();
        assertEquals(1, found.size(), "exactly one group expected");
        return found.getFirst();
    }

    private GroupMember onlyMember() {
        List<GroupMember> found = members();
        assertEquals(1, found.size(), "exactly one member expected");
        return found.getFirst();
    }

    private RoutineSchedule onlySchedule() {
        List<RoutineSchedule> found = schedules();
        assertEquals(1, found.size(), "exactly one schedule expected");
        return found.getFirst();
    }

    private List<RoutineGroup> groups() {
        return entityManager.createQuery("select g from RoutineGroup g", RoutineGroup.class).getResultList();
    }

    private List<GroupMember> members() {
        return entityManager.createQuery("select m from GroupMember m", GroupMember.class).getResultList();
    }

    private List<RoutineSchedule> schedules() {
        return entityManager.createQuery("select s from RoutineSchedule s", RoutineSchedule.class).getResultList();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private record Fixture(Long userId, Long routineDefinitionId) {
    }
}

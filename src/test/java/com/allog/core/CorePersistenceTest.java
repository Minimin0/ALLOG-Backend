package com.allog.core;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineKey;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import com.allog.verification.template.VerificationTemplateCatalog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CorePersistenceTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VerificationTemplateCatalog verificationTemplateCatalog;

    @Test
    void appliesFlywayMigrationBeforeJpaValidation() {
        Integer migrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class
        );

        assertEquals(1, migrations);
    }

    @Test
    void v7PersistsCanonicalRoutineKeyAndKeepsLegacyRowsNullable() {
        RoutineDefinition keyed = new RoutineDefinition(
                new RoutineKey(" core_test_routine "),
                "test routine",
                null
        );
        RoutineDefinition legacy = new RoutineDefinition("legacy routine", null);
        entityManager.persist(keyed);
        entityManager.persist(legacy);
        entityManager.flush();
        entityManager.clear();

        assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '7' and success = true",
                Integer.class
        ));
        assertEquals("CORE_TEST_ROUTINE", entityManager.find(RoutineDefinition.class, keyed.getId())
                .getRoutineKey().value());
        assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from routine_definition where id = ? and routine_key is null",
                Integer.class,
                legacy.getId()
        ));
    }

    @Test
    void v8PersistsExactGroupVerificationBindingAndKeepsLegacyRowsNullable() {
        Fixture legacy = fixture();
        RoutineGroup bound = new RoutineGroup(
                legacy.definition(),
                legacy.user(),
                "meal verification",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.DRAFT,
                5,
                1,
                verificationTemplateCatalog.requireTemplate(VerificationTemplateCatalog.MEAL_PHOTO_RECORD)
        );
        entityManager.persist(bound);
        entityManager.flush();
        entityManager.clear();

        RoutineGroup found = entityManager.find(RoutineGroup.class, bound.getId());
        assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '8' and success = true",
                Integer.class
        ));
        assertEquals(VerificationTemplateCatalog.MEAL_PHOTO_RECORD, found.getVerificationTemplateKey());
        assertEquals(VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1, found.getVerificationCriteriaReference());
        assertNull(entityManager.find(RoutineGroup.class, legacy.group().getId()).getVerificationTemplateKey());
    }

    @Test
    void v8RejectsOneSidedGroupVerificationBinding() {
        Fixture fixture = fixture();
        entityManager.flush();

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "update routine_group set verification_template_key = ? where id = ?",
                VerificationTemplateCatalog.MEAL_PHOTO_RECORD.value(),
                fixture.group().getId()
        ));
    }

    @Test
    void preventsDuplicateNormalizedRoutineKey() {
        entityManager.persist(new RoutineDefinition(
                new RoutineKey("CORE_TEST_ROUTINE"),
                "first",
                null
        ));
        entityManager.flush();

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(new RoutineDefinition(
                    new RoutineKey("core_test_routine"),
                    "second",
                    null
            ));
            entityManager.flush();
        });
    }

    @Test
    void persistsUserDefinitionAndGroupWithTimestamps() {
        Fixture fixture = fixture();
        entityManager.flush();

        assertNotNull(fixture.user().getId());
        assertNotNull(fixture.definition().getId());
        assertNotNull(fixture.group().getId());
        assertNotNull(fixture.group().getCreatedAt());
        assertEquals(5, fixture.group().getMaxMembers());
        assertEquals(3, fixture.group().getRequiredCompletionCount());
    }

    @Test
    void persistsDailySchedule() {
        RoutineSchedule schedule = schedule(fixture().group(), ScheduleType.DAILY, Set.of());
        entityManager.persist(schedule);
        entityManager.flush();

        assertNotNull(schedule.getId());
        assertTrue(schedule.getSpecificDays().isEmpty());
    }

    @Test
    void persistsSpecificDaysWithoutDuplicates() {
        RoutineSchedule schedule = schedule(
                fixture().group(),
                ScheduleType.SPECIFIC_DAYS,
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        );
        entityManager.persist(schedule);
        entityManager.flush();
        entityManager.clear();

        RoutineSchedule found = entityManager.find(RoutineSchedule.class, schedule.getId());
        assertEquals(Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), found.getSpecificDays());
    }

    @Test
    void preventsDuplicateScheduleForGroup() {
        RoutineGroup group = fixture().group();
        entityManager.persist(schedule(group, ScheduleType.DAILY, Set.of()));

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(schedule(group, ScheduleType.DAILY, Set.of()));
            entityManager.flush();
        });
    }

    @Test
    void persistsAndFindsMemberByGroupAndUser() {
        Fixture fixture = fixture();
        GroupMember member = new GroupMember(
                fixture.group(),
                fixture.user(),
                GroupMemberRole.OWNER,
                GroupMemberStatus.ACTIVE,
                Instant.parse("2026-08-11T09:00:00Z")
        );
        groupMemberRepository.saveAndFlush(member);
        entityManager.clear();

        GroupMember found = groupMemberRepository
                .findByRoutineGroup_IdAndUser_Id(fixture.group().getId(), fixture.user().getId())
                .orElseThrow();

        assertEquals(GroupMemberRole.OWNER, found.getRole());
        assertEquals(GroupMemberStatus.ACTIVE, found.getStatus());
    }

    @Test
    void preventsDuplicateMembership() {
        Fixture fixture = fixture();
        Instant joinedAt = Instant.parse("2026-08-11T09:00:00Z");
        entityManager.persist(new GroupMember(
                fixture.group(), fixture.user(), GroupMemberRole.OWNER, GroupMemberStatus.ACTIVE, joinedAt
        ));

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(new GroupMember(
                    fixture.group(), fixture.user(), GroupMemberRole.MEMBER, GroupMemberStatus.JOINED, joinedAt
            ));
            entityManager.flush();
        });
    }

    private Fixture fixture() {
        User user = User.create();
        RoutineDefinition definition = new RoutineDefinition("물 마시기", "물을 꾸준히 마시는 루틴");
        entityManager.persist(user);
        entityManager.persist(definition);

        RoutineGroup group = new RoutineGroup(
                definition,
                user,
                "건강한 물 마시기",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.DRAFT,
                5,
                3
        );
        entityManager.persist(group);
        return new Fixture(user, definition, group);
    }

    private RoutineSchedule schedule(RoutineGroup group, ScheduleType type, Set<DayOfWeek> days) {
        return new RoutineSchedule(
                group,
                type,
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 17),
                LocalTime.of(22, 0),
                "Asia/Seoul",
                days
        );
    }

    private record Fixture(User user, RoutineDefinition definition, RoutineGroup group) {
    }
}

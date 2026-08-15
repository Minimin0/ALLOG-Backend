package com.allog.verification;

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
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.repository.VerificationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VerificationPersistenceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private VerificationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesFlywayV2BeforeHibernateValidation() {
        Integer migrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = TRUE",
                Integer.class
        );

        assertEquals(1, migrations);
    }

    @Test
    void persistsAggregateWithMemberAndScheduleRelations() {
        Fixture fixture = fixture();
        Verification saved = repository.saveAndFlush(Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)
        ));
        entityManager.clear();

        Verification found = repository
                .findByGroupMember_IdAndRoutineSchedule_IdAndScheduledDate(
                        fixture.member().getId(),
                        fixture.schedule().getId(),
                        LocalDate.of(2026, 8, 11)
                )
                .orElseThrow();

        assertNotNull(saved.getId());
        assertEquals(fixture.member().getId(), found.getGroupMember().getId());
        assertEquals(fixture.schedule().getId(), found.getRoutineSchedule().getId());
        assertEquals(VerificationStatus.PENDING_UPLOAD, found.getStatus());
    }

    @Test
    void databaseRejectsDuplicateScheduledOpportunity() {
        Fixture fixture = fixture();
        LocalDate scheduledDate = LocalDate.of(2026, 8, 11);
        repository.saveAndFlush(Verification.create(fixture.member(), fixture.schedule(), scheduledDate));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(Verification.create(
                        fixture.member(), fixture.schedule(), scheduledDate
                ))
        );
    }

    @Test
    void allowsDifferentScheduledDates() {
        Fixture fixture = fixture();
        repository.save(Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)
        ));
        repository.save(Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 12)
        ));
        repository.flush();

        assertEquals(2, repository.count());
    }

    @Test
    void findsOnlyApprovedVerificationsInScheduleRange() {
        Fixture fixture = fixture();
        Verification first = approved(fixture, LocalDate.of(2026, 8, 11));
        Verification second = approved(fixture, LocalDate.of(2026, 8, 13));
        Verification rejected = Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 12)
        );
        rejected.submit(CLOCK);
        rejected.startProcessing();
        rejected.reject("operator note");
        repository.saveAllAndFlush(List.of(second, rejected, first));

        List<Verification> approved = repository
                .findAllByGroupMember_IdAndRoutineSchedule_IdAndStatusAndScheduledDateBetweenOrderByScheduledDateAsc(
                        fixture.member().getId(),
                        fixture.schedule().getId(),
                        VerificationStatus.APPROVED,
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 8, 16)
                );

        assertEquals(
                List.of(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13)),
                approved.stream().map(Verification::getScheduledDate).toList()
        );
    }

    private Verification approved(Fixture fixture, LocalDate date) {
        Verification verification = Verification.create(fixture.member(), fixture.schedule(), date);
        verification.submit(CLOCK);
        verification.startProcessing();
        verification.approve(CLOCK);
        return verification;
    }

    private Fixture fixture() {
        User user = User.create();
        RoutineDefinition definition = new RoutineDefinition("물 마시기", null);
        entityManager.persist(user);
        entityManager.persist(definition);
        RoutineGroup group = new RoutineGroup(
                definition,
                user,
                "건강한 물 마시기",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.ACTIVE,
                5,
                3
        );
        entityManager.persist(group);
        RoutineSchedule schedule = new RoutineSchedule(
                group,
                ScheduleType.DAILY,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 16),
                LocalTime.of(23, 0),
                "Asia/Seoul",
                Set.of()
        );
        entityManager.persist(schedule);
        GroupMember member = new GroupMember(
                group,
                user,
                GroupMemberRole.OWNER,
                GroupMemberStatus.ACTIVE,
                Instant.parse("2026-08-01T09:00:00Z")
        );
        entityManager.persist(member);
        entityManager.flush();
        return new Fixture(member, schedule);
    }

    private record Fixture(GroupMember member, RoutineSchedule schedule) {
    }
}

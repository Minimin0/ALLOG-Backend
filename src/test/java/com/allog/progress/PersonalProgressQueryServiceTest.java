package com.allog.progress;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.progress.domain.PersonalProgressFacts;
import com.allog.progress.service.PersonalProgressQueryService;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import com.allog.verification.domain.Verification;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PersonalProgressQueryServiceTest {

    private static final Clock TRANSITION_CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T10:00:00Z"),
            ZoneOffset.UTC
    );

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PersonalProgressQueryService queryService;

    @Test
    void loadsEveryVerificationStatusAndCalculatesReadOnlyFacts() {
        Fixture fixture = fixture();
        Verification approved = Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 10)
        );
        approved.submit(TRANSITION_CLOCK);
        approved.startProcessing();
        approved.approve(TRANSITION_CLOCK);
        Verification processing = Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 11)
        );
        processing.submit(TRANSITION_CLOCK);
        processing.startProcessing();
        entityManager.persist(approved);
        entityManager.persist(processing);
        entityManager.flush();

        PersonalProgressFacts facts = queryService.calculate(
                fixture.member(),
                fixture.schedule(),
                Clock.fixed(Instant.parse("2026-08-11T14:30:00Z"), ZoneOffset.UTC)
        );

        assertAll(
                () -> assertEquals(1, facts.completedCount()),
                () -> assertEquals(1, facts.currentStreak()),
                () -> assertTrue(facts.todayVerificationPending()),
                () -> assertEquals(1, facts.pendingDecisionCount()),
                () -> assertEquals(1, facts.remainingOpportunityCount())
        );
    }

    @Test
    void doesNotHideVerificationOutsideTheScheduleRange() {
        Fixture fixture = fixture();
        entityManager.persist(Verification.create(
                fixture.member(), fixture.schedule(), LocalDate.of(2026, 8, 13)
        ));
        entityManager.flush();

        assertThrows(
                IllegalStateException.class,
                () -> queryService.calculate(fixture.member(), fixture.schedule(), TRANSITION_CLOCK)
        );
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
                2
        );
        entityManager.persist(group);
        RoutineSchedule schedule = new RoutineSchedule(
                group,
                ScheduleType.DAILY,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12),
                LocalTime.of(23, 0),
                "Asia/Seoul",
                Set.of()
        );
        entityManager.persist(schedule);
        GroupMember member = new GroupMember(
                group,
                user,
                GroupMemberRole.MEMBER,
                GroupMemberStatus.JOINED,
                Instant.parse("2026-08-01T09:00:00Z")
        );
        member.startParticipation(Instant.parse("2026-08-01T09:00:00Z"));
        entityManager.persist(member);
        entityManager.flush();
        return new Fixture(member, schedule);
    }

    private record Fixture(GroupMember member, RoutineSchedule schedule) {
    }
}

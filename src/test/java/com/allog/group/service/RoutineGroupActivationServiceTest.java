package com.allog.group.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.group.repository.RoutineGroupRepository;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=${ACTIVATION_TEST_DB_URL:jdbc:h2:mem:membership-lifecycle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${ACTIVATION_TEST_DB_USERNAME:sa}",
        "spring.datasource.password=${ACTIVATION_TEST_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=${ACTIVATION_TEST_DB_DRIVER:org.h2.Driver}"
})
@ActiveProfiles("test")
class RoutineGroupActivationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC);
    private static final Instant ACTIVATION_TIME = Instant.parse("2026-08-11T10:00:00Z");

    @Autowired
    private RoutineGroupActivationService service;

    @Autowired
    private RoutineGroupRepository routineGroupRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

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
    }

    @Test
    void appliesFlywayV3BeforeHibernateValidation() {
        Integer migrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1', '2', '3') AND success = TRUE",
                Integer.class
        );

        assertEquals(3, migrations);
    }

    @Test
    void atomicallyActivatesLateButFeasibleGroupWithOneClockReadAndTimestamp() {
        Fixture fixture = fixture(
                RoutineGroupStatus.FULL,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.LEFT,
                GroupMemberStatus.REMOVED
        );
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(ACTIVATION_TIME);

        service.activate(fixture.groupId(), clock);

        RoutineGroup group = inTransaction(() -> routineGroupRepository.findById(fixture.groupId()).orElseThrow());
        Map<Long, GroupMember> members = membersById(fixture.groupId());
        verify(clock, times(1)).instant();
        assertEquals(RoutineGroupStatus.ACTIVE, group.getStatus());
        assertStarted(members.get(fixture.memberIds().get(0)));
        assertStarted(members.get(fixture.memberIds().get(1)));
        assertEquals(GroupMemberStatus.LEFT, members.get(fixture.memberIds().get(2)).getStatus());
        assertNull(members.get(fixture.memberIds().get(2)).getParticipationStartedAt());
        assertEquals(GroupMemberStatus.REMOVED, members.get(fixture.memberIds().get(3)).getStatus());
        assertNull(members.get(fixture.memberIds().get(3)).getParticipationStartedAt());
    }

    @Test
    void rejectsActivationWithoutJoinedParticipant() {
        Fixture fixture = fixture(
                RoutineGroupStatus.FULL,
                GroupMemberStatus.LEFT,
                GroupMemberStatus.REMOVED
        );

        assertThrows(IllegalStateException.class, () -> service.activate(fixture.groupId(), CLOCK));

        assertEquals(
                RoutineGroupStatus.FULL,
                inTransaction(() -> routineGroupRepository.findById(fixture.groupId()).orElseThrow().getStatus())
        );
    }

    @Test
    void rejectsActivationWithoutScheduleAndKeepsMemberJoined() {
        Fixture fixture = fixtureWithoutSchedule(
                RoutineGroupStatus.FULL,
                GroupMemberStatus.JOINED
        );

        assertThrows(IllegalStateException.class, () -> service.activate(fixture.groupId(), CLOCK));

        assertActivationUnchanged(fixture, RoutineGroupStatus.FULL);
    }

    @Test
    void rejectsLateActivationWhenRemainingOpportunitiesCannotMeetRequirement() {
        Fixture fixture = fixtureWithSchedule(
                RoutineGroupStatus.FULL,
                5,
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 13),
                GroupMemberStatus.JOINED,
                GroupMemberStatus.JOINED
        );

        assertThrows(IllegalStateException.class, () -> service.activate(fixture.groupId(), CLOCK));

        assertActivationUnchanged(fixture, RoutineGroupStatus.FULL);
    }

    @Test
    void rejectsActivationWithZeroRemainingOpportunities() {
        Fixture fixture = fixtureWithSchedule(
                RoutineGroupStatus.FULL,
                1,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11),
                GroupMemberStatus.JOINED
        );
        Clock exactFinalDeadline = Clock.fixed(
                Instant.parse("2026-08-11T14:00:00Z"),
                ZoneOffset.UTC
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.activate(fixture.groupId(), exactFinalDeadline)
        );

        assertActivationUnchanged(fixture, RoutineGroupStatus.FULL);
    }

    @Test
    void inconsistentMemberRollsBackTheWholeActivation() {
        Fixture fixture = fixture(
                RoutineGroupStatus.FULL,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.ACTIVE
        );

        assertThrows(IllegalStateException.class, () -> service.activate(fixture.groupId(), CLOCK));

        Map<Long, GroupMember> members = membersById(fixture.groupId());
        assertEquals(
                RoutineGroupStatus.FULL,
                inTransaction(() -> routineGroupRepository.findById(fixture.groupId()).orElseThrow().getStatus())
        );
        assertEquals(GroupMemberStatus.JOINED, members.get(fixture.memberIds().get(0)).getStatus());
        assertNull(members.get(fixture.memberIds().get(0)).getParticipationStartedAt());
        assertEquals(GroupMemberStatus.ACTIVE, members.get(fixture.memberIds().get(1)).getStatus());
        assertNull(members.get(fixture.memberIds().get(1)).getParticipationStartedAt());
    }

    @Test
    void rollsBackGroupAndMemberActivationTogether() {
        Fixture fixture = fixture(
                RoutineGroupStatus.FULL,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.JOINED
        );

        transaction.executeWithoutResult(status -> {
            service.activate(fixture.groupId(), CLOCK);
            status.setRollbackOnly();
        });

        Map<Long, GroupMember> members = membersById(fixture.groupId());
        assertEquals(
                RoutineGroupStatus.FULL,
                inTransaction(() -> routineGroupRepository.findById(fixture.groupId()).orElseThrow().getStatus())
        );
        for (Long memberId : fixture.memberIds()) {
            assertEquals(GroupMemberStatus.JOINED, members.get(memberId).getStatus());
            assertNull(members.get(memberId).getParticipationStartedAt());
        }
    }

    @Test
    void rejectsJoinedMemberThatAlreadyHasParticipationHistory() {
        Fixture fixture = fixture(RoutineGroupStatus.FULL, GroupMemberStatus.JOINED);
        jdbcTemplate.update(
                "UPDATE group_member SET participation_started_at = ? WHERE id = ?",
                Timestamp.from(ACTIVATION_TIME),
                fixture.memberIds().getFirst()
        );

        assertThrows(IllegalStateException.class, () -> service.activate(fixture.groupId(), CLOCK));

        assertEquals(
                RoutineGroupStatus.FULL,
                inTransaction(() -> routineGroupRepository.findById(fixture.groupId()).orElseThrow().getStatus())
        );
    }

    @Test
    void eligibilityQueryUsesParticipationHistoryInsteadOfCurrentStatus() {
        Fixture fixture = fixture(
                RoutineGroupStatus.FULL,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.JOINED
        );
        service.activate(fixture.groupId(), CLOCK);
        List<GroupMemberStatus> terminalAndCurrentStatuses = List.of(
                GroupMemberStatus.ACTIVE,
                GroupMemberStatus.COMPLETED,
                GroupMemberStatus.FAILED,
                GroupMemberStatus.LEFT,
                GroupMemberStatus.REMOVED
        );
        for (int index = 0; index < terminalAndCurrentStatuses.size(); index++) {
            jdbcTemplate.update(
                    "UPDATE group_member SET status = ? WHERE id = ?",
                    terminalAndCurrentStatuses.get(index).name(),
                    fixture.memberIds().get(index)
            );
        }
        Long neverStartedMemberId = addJoinedMember(fixture.groupId());

        Set<Long> eligibleIds = inTransaction(() -> groupMemberRepository
                .findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(fixture.groupId())
                .stream()
                .map(GroupMember::getId)
                .collect(java.util.stream.Collectors.toSet()));

        assertEquals(Set.copyOf(fixture.memberIds()), eligibleIds);
        assertFalse(eligibleIds.contains(neverStartedMemberId));
    }

    private void assertStarted(GroupMember member) {
        assertEquals(GroupMemberStatus.ACTIVE, member.getStatus());
        assertEquals(ACTIVATION_TIME, member.getParticipationStartedAt());
    }

    private void assertActivationUnchanged(Fixture fixture, RoutineGroupStatus expectedGroupStatus) {
        assertEquals(
                expectedGroupStatus,
                inTransaction(() -> routineGroupRepository.findById(fixture.groupId()).orElseThrow().getStatus())
        );
        for (GroupMember member : membersById(fixture.groupId()).values()) {
            assertEquals(GroupMemberStatus.JOINED, member.getStatus());
            assertNull(member.getParticipationStartedAt());
        }
    }

    private Map<Long, GroupMember> membersById(Long groupId) {
        return inTransaction(() -> {
            Map<Long, GroupMember> result = new HashMap<>();
            groupMemberRepository.findAllByRoutineGroup_Id(groupId)
                    .forEach(member -> result.put(member.getId(), member));
            return result;
        });
    }

    private Long addJoinedMember(Long groupId) {
        return inTransaction(() -> {
            RoutineGroup group = entityManager.find(RoutineGroup.class, groupId);
            User user = User.create();
            entityManager.persist(user);
            GroupMember member = new GroupMember(
                    group,
                    user,
                    GroupMemberRole.MEMBER,
                    GroupMemberStatus.JOINED,
                    Instant.parse("2026-08-11T11:00:00Z")
            );
            entityManager.persist(member);
            entityManager.flush();
            return member.getId();
        });
    }

    private Fixture fixture(RoutineGroupStatus groupStatus, GroupMemberStatus... memberStatuses) {
        return fixtureWithSchedule(
                groupStatus,
                5,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 15),
                memberStatuses
        );
    }

    private Fixture fixtureWithoutSchedule(
            RoutineGroupStatus groupStatus,
            GroupMemberStatus... memberStatuses
    ) {
        return fixture(groupStatus, 5, null, null, memberStatuses);
    }

    private Fixture fixtureWithSchedule(
            RoutineGroupStatus groupStatus,
            int requiredCompletionCount,
            LocalDate scheduleStart,
            LocalDate scheduleEnd,
            GroupMemberStatus... memberStatuses
    ) {
        return fixture(
                groupStatus,
                requiredCompletionCount,
                scheduleStart,
                scheduleEnd,
                memberStatuses
        );
    }

    private Fixture fixture(
            RoutineGroupStatus groupStatus,
            int requiredCompletionCount,
            LocalDate scheduleStart,
            LocalDate scheduleEnd,
            GroupMemberStatus... memberStatuses
    ) {
        return inTransaction(() -> {
            User owner = User.create();
            RoutineDefinition definition = new RoutineDefinition("물 마시기", null);
            entityManager.persist(owner);
            entityManager.persist(definition);
            long joinedCount = java.util.Arrays.stream(memberStatuses)
                    .filter(status -> status == GroupMemberStatus.JOINED)
                    .count();
            RoutineGroup group = new RoutineGroup(
                    definition,
                    owner,
                    "건강한 물 마시기",
                    GroupVisibility.PUBLIC,
                    groupStatus,
                    (int) Math.max(1, joinedCount),
                    requiredCompletionCount
            );
            entityManager.persist(group);
            if (scheduleStart != null) {
                entityManager.persist(new RoutineSchedule(
                        group,
                        ScheduleType.DAILY,
                        scheduleStart,
                        scheduleEnd,
                        LocalTime.of(23, 0),
                        "Asia/Seoul",
                        Set.of()
                ));
            }

            List<Long> memberIds = new ArrayList<>();
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
                        Instant.parse("2026-08-10T09:00:00Z").plusSeconds(60L * index)
                );
                entityManager.persist(member);
                entityManager.flush();
                memberIds.add(member.getId());
            }
            return new Fixture(group.getId(), List.copyOf(memberIds));
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private record Fixture(Long groupId, List<Long> memberIds) {
    }
}

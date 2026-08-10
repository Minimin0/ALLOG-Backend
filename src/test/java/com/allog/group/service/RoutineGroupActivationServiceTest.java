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
import java.time.LocalDateTime;
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

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:membership-lifecycle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class RoutineGroupActivationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime ACTIVATION_TIME = LocalDateTime.of(2026, 8, 11, 10, 0);

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
    void atomicallyActivatesJoinedMembersWithOneTimestamp() {
        Fixture fixture = fixture(
                RoutineGroupStatus.RECRUITING,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.LEFT,
                GroupMemberStatus.REMOVED
        );

        service.activate(fixture.groupId(), CLOCK);

        RoutineGroup group = inTransaction(() -> routineGroupRepository.findById(fixture.groupId()).orElseThrow());
        Map<Long, GroupMember> members = membersById(fixture.groupId());
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
                RoutineGroupStatus.RECRUITING,
                GroupMemberStatus.LEFT,
                GroupMemberStatus.REMOVED
        );

        assertThrows(IllegalStateException.class, () -> service.activate(fixture.groupId(), CLOCK));

        assertEquals(
                RoutineGroupStatus.RECRUITING,
                inTransaction(() -> routineGroupRepository.findById(fixture.groupId()).orElseThrow().getStatus())
        );
    }

    @Test
    void inconsistentMemberRollsBackTheWholeActivation() {
        Fixture fixture = fixture(
                RoutineGroupStatus.RECRUITING,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.ACTIVE
        );

        assertThrows(IllegalStateException.class, () -> service.activate(fixture.groupId(), CLOCK));

        Map<Long, GroupMember> members = membersById(fixture.groupId());
        assertEquals(
                RoutineGroupStatus.RECRUITING,
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
                RoutineGroupStatus.RECRUITING,
                GroupMemberStatus.JOINED,
                GroupMemberStatus.JOINED
        );

        transaction.executeWithoutResult(status -> {
            service.activate(fixture.groupId(), CLOCK);
            status.setRollbackOnly();
        });

        Map<Long, GroupMember> members = membersById(fixture.groupId());
        assertEquals(
                RoutineGroupStatus.RECRUITING,
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
                Timestamp.valueOf(ACTIVATION_TIME),
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
                    LocalDateTime.of(2026, 8, 11, 11, 0)
            );
            entityManager.persist(member);
            entityManager.flush();
            return member.getId();
        });
    }

    private Fixture fixture(RoutineGroupStatus groupStatus, GroupMemberStatus... memberStatuses) {
        return inTransaction(() -> {
            User owner = User.create();
            RoutineDefinition definition = new RoutineDefinition("물 마시기", null);
            entityManager.persist(owner);
            entityManager.persist(definition);
            RoutineGroup group = new RoutineGroup(
                    definition,
                    owner,
                    "건강한 물 마시기",
                    GroupVisibility.PUBLIC,
                    groupStatus,
                    10,
                    5
            );
            entityManager.persist(group);

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
                        LocalDateTime.of(2026, 8, 10, 9, index)
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

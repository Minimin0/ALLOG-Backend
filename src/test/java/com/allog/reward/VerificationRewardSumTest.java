package com.allog.reward;

import com.allog.reward.repository.VerificationRewardRepository;
import com.allog.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A reward points at a verification, not at a user, so the total has to travel
 * reward → verification → group_member → user. These fixtures build that whole chain rather than
 * assuming the join works.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VerificationRewardSumTest {

    @Autowired
    private VerificationRewardRepository rewardRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aMemberWithNoApprovalsHasEarnedNothingRatherThanNull() {
        assertEquals(0L, rewardRepository.sumPointsByUserId(newUserId()));
    }

    @Test
    void sumsOnlyTheRequestedMembersRewards() {
        Long groupId = newGroup();
        Long scheduleId = newSchedule(groupId);

        Long alice = newUserId();
        Long bob = newUserId();
        Long aliceMember = newMember(groupId, alice);
        Long bobMember = newMember(groupId, bob);

        reward(newVerification(aliceMember, scheduleId, "2026-08-01"), 10);
        reward(newVerification(aliceMember, scheduleId, "2026-08-02"), 10);
        reward(newVerification(bobMember, scheduleId, "2026-08-01"), 10);

        assertEquals(20L, rewardRepository.sumPointsByUserId(alice));
        assertEquals(10L, rewardRepository.sumPointsByUserId(bob), "one member's points must not leak");
    }

    /**
     * The ledger is append-only and no clawback policy exists, so an invalidated verification keeps
     * its points. This pins that behaviour rather than leaving it to be discovered.
     */
    @Test
    void pointsFromAnInvalidatedVerificationStillCount() {
        Long groupId = newGroup();
        Long scheduleId = newSchedule(groupId);
        Long userId = newUserId();
        Long memberId = newMember(groupId, userId);

        Long verificationId = newVerification(memberId, scheduleId, "2026-08-03");
        reward(verificationId, 10);
        jdbcTemplate.update(
                "UPDATE verification SET status = 'INVALIDATED', invalidated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ?", verificationId);

        assertEquals(10L, rewardRepository.sumPointsByUserId(userId));
    }

    private Long newUserId() {
        User user = User.create();
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private Long newGroup() {
        Long ownerId = newUserId();
        jdbcTemplate.update(
                "INSERT INTO routine_definition (name, created_at, updated_at)"
                        + " VALUES ('reward sum routine', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        Long definitionId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM routine_definition", Long.class);
        jdbcTemplate.update(
                "INSERT INTO routine_group (routine_definition_id, created_by_user_id, name, visibility,"
                        + " status, max_members, required_completion_count, created_at, updated_at)"
                        + " VALUES (?, ?, 'reward sum group', 'PUBLIC', 'ACTIVE', 10, 1,"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                definitionId, ownerId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM routine_group", Long.class);
    }

    private Long newSchedule(Long groupId) {
        jdbcTemplate.update(
                "INSERT INTO routine_schedule (routine_group_id, schedule_type, start_date, end_date,"
                        + " deadline_time, timezone, created_at, updated_at)"
                        + " VALUES (?, 'DAILY', '2026-08-01', '2026-08-31', '23:59:59', 'Asia/Seoul',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                groupId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM routine_schedule", Long.class);
    }

    private Long newMember(Long groupId, Long userId) {
        jdbcTemplate.update(
                "INSERT INTO group_member (routine_group_id, user_id, role, status, joined_at,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, 'MEMBER', 'ACTIVE', CURRENT_TIMESTAMP,"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                groupId, userId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM group_member", Long.class);
    }

    private Long newVerification(Long memberId, Long scheduleId, String scheduledDate) {
        jdbcTemplate.update(
                "INSERT INTO verification (group_member_id, routine_schedule_id, scheduled_date, status,"
                        + " submitted_at, approved_at, attempt_count, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'APPROVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1,"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                memberId, scheduleId, java.sql.Date.valueOf(scheduledDate));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM verification", Long.class);
    }

    private void reward(Long verificationId, int points) {
        jdbcTemplate.update(
                "INSERT INTO verification_reward (verification_id, points, granted_at, created_at, updated_at)"
                        + " VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                verificationId, points);
    }
}

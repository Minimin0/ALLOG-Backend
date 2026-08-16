package com.allog.verification.repository;

import com.allog.group.domain.GroupMember;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VerificationRepository extends JpaRepository<Verification, Long> {

    boolean existsByGroupMemberAndRoutineScheduleAndScheduledDate(
            GroupMember groupMember,
            RoutineSchedule routineSchedule,
            LocalDate scheduledDate
    );

    Optional<Verification> findByGroupMember_IdAndRoutineSchedule_IdAndScheduledDate(
            Long groupMemberId,
            Long routineScheduleId,
            LocalDate scheduledDate
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select verification
            from Verification verification
            where verification.groupMember.id = :groupMemberId
              and verification.routineSchedule.id = :routineScheduleId
              and verification.scheduledDate = :scheduledDate
            """)
    Optional<Verification> findCurrentForUpdate(
            @Param("groupMemberId") Long groupMemberId,
            @Param("routineScheduleId") Long routineScheduleId,
            @Param("scheduledDate") LocalDate scheduledDate
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select verification from Verification verification where verification.id = :verificationId")
    Optional<Verification> findByIdForUpdate(@Param("verificationId") Long verificationId);

    /**
     * The review queue. Oldest first, so the member who has been waiting longest is settled first.
     * Media and analysis are joined in rather than fetched per row: a queue read must not turn into
     * two extra queries per held verification.
     */
    @Query("""
            select new com.allog.verification.repository.PendingReviewRow(
                verification.id,
                member.user.id,
                group.id,
                verification.scheduledDate,
                verification.attemptCount,
                verification.createdAt,
                verification.submittedAt,
                media.objectKey,
                analysis.recommendation,
                analysis.reasonCode,
                analysis.objectPresence,
                analysis.anomalyDetected,
                analysis.criteriaVersion
            )
            from Verification verification
            join verification.groupMember member
            join verification.routineSchedule schedule
            join schedule.routineGroup group
            left join VerificationMedia media on media.verification = verification
            left join VerificationAnalysis analysis on analysis.verification = verification
            where verification.status = :status
            order by verification.createdAt asc, verification.id asc
            """)
    List<PendingReviewRow> findReviewQueue(
            @Param("status") VerificationStatus status,
            Pageable pageable
    );

    List<Verification> findAllByGroupMember_IdAndRoutineSchedule_IdAndStatusAndScheduledDateBetweenOrderByScheduledDateAsc(
            Long groupMemberId,
            Long routineScheduleId,
            VerificationStatus status,
            LocalDate startDate,
            LocalDate endDate
    );

    List<Verification> findAllByGroupMemberAndRoutineScheduleOrderByScheduledDateAsc(
            GroupMember groupMember,
            RoutineSchedule routineSchedule
    );

    @Query("""
            select verification
            from Verification verification
            where verification.routineSchedule.id = :scheduleId
              and verification.groupMember.id in :memberIds
            order by verification.groupMember.id, verification.scheduledDate
            """)
    List<Verification> findAllForProgressBatch(
            @Param("scheduleId") Long scheduleId,
            @Param("memberIds") Collection<Long> memberIds
    );
}

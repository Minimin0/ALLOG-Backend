package com.allog.verification.domain;

import com.allog.common.persistence.BaseTimeEntity;
import com.allog.group.domain.GroupMember;
import com.allog.routine.domain.RoutineSchedule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(
        name = "verification",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_verification_member_schedule_date",
                columnNames = {"group_member_id", "routine_schedule_id", "scheduled_date"}
        ),
        indexes = {
                @Index(
                        name = "idx_verification_member_status_date",
                        columnList = "group_member_id,status,scheduled_date"
                ),
                @Index(
                        name = "idx_verification_schedule_date_status",
                        columnList = "routine_schedule_id,scheduled_date,status"
                )
        }
)
public class Verification extends BaseTimeEntity {

    /** MVP guided retry: the initial submission plus exactly one retry. */
    private static final int MAX_ATTEMPTS = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_member_id", nullable = false)
    private GroupMember groupMember;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "routine_schedule_id", nullable = false)
    private RoutineSchedule routineSchedule;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VerificationStatus status;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    /** User submissions spent on this opportunity: the first one plus at most one guided retry. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    protected Verification() {
    }

    private Verification(GroupMember groupMember, RoutineSchedule routineSchedule, LocalDate scheduledDate) {
        this.groupMember = Objects.requireNonNull(groupMember, "groupMember must not be null");
        this.routineSchedule = Objects.requireNonNull(routineSchedule, "routineSchedule must not be null");
        this.scheduledDate = Objects.requireNonNull(scheduledDate, "scheduledDate must not be null");
        this.status = VerificationStatus.PENDING_UPLOAD;
    }

    public static Verification create(
            GroupMember groupMember,
            RoutineSchedule routineSchedule,
            LocalDate scheduledDate
    ) {
        return new Verification(groupMember, routineSchedule, scheduledDate);
    }

    public void submit(Clock clock) {
        requireTransition(VerificationStatus.SUBMITTED, VerificationStatus.PENDING_UPLOAD, VerificationStatus.RETRY_REQUIRED);
        if (attemptCount >= MAX_ATTEMPTS) {
            throw new IllegalStateException("verification has no submission attempt left");
        }
        Instant eventTime = requireClock(clock).instant();
        if (submittedAt != null && eventTime.isBefore(submittedAt)) {
            throw new IllegalStateException("submittedAt must not move backwards");
        }
        status = VerificationStatus.SUBMITTED;
        submittedAt = eventTime;
        attemptCount++;
    }

    /** False once the guided retry has been spent, which is what turns a second rejection into a hold. */
    public boolean hasRetryRemaining() {
        return attemptCount < MAX_ATTEMPTS;
    }

    public void startProcessing() {
        transitionTo(VerificationStatus.PROCESSING, VerificationStatus.SUBMITTED);
    }

    public void approve(Clock clock) {
        // SUBMITTED도 허용한다: AI 처리 중 상태의 authority는 VerificationAnalysis.status이고,
        // Verification은 analysis가 끝날 때까지 SUBMITTED에 머문다(startProcessing() production caller 없음).
        requireTransition(
                VerificationStatus.APPROVED,
                VerificationStatus.SUBMITTED,
                VerificationStatus.PROCESSING,
                VerificationStatus.REVIEW_REQUIRED
        );
        Instant eventTime = requireClock(clock).instant();
        if (submittedAt == null) {
            throw new IllegalStateException("approval requires submittedAt");
        }
        if (eventTime.isBefore(submittedAt)) {
            throw new IllegalStateException("approvedAt must not be before submittedAt");
        }
        status = VerificationStatus.APPROVED;
        approvedAt = eventTime;
    }

    public void requestReview() {
        transitionTo(VerificationStatus.REVIEW_REQUIRED, VerificationStatus.SUBMITTED, VerificationStatus.PROCESSING);
    }

    /** Guarded so a verification can never be sent back to the user without an attempt left to spend. */
    public void requestRetry() {
        if (!hasRetryRemaining()) {
            throw new IllegalStateException("verification has no retry left");
        }
        transitionTo(
                VerificationStatus.RETRY_REQUIRED,
                VerificationStatus.SUBMITTED,
                VerificationStatus.PROCESSING,
                VerificationStatus.REVIEW_REQUIRED
        );
    }

    public void reject() {
        transitionTo(VerificationStatus.REJECTED, VerificationStatus.PROCESSING, VerificationStatus.REVIEW_REQUIRED);
    }

    public void invalidate(Clock clock) {
        requireTransition(VerificationStatus.INVALIDATED, VerificationStatus.APPROVED);
        Instant eventTime = requireClock(clock).instant();
        if (approvedAt == null) {
            throw new IllegalStateException("invalidation requires approvedAt");
        }
        if (eventTime.isBefore(approvedAt)) {
            throw new IllegalStateException("invalidatedAt must not be before approvedAt");
        }
        status = VerificationStatus.INVALIDATED;
        invalidatedAt = eventTime;
    }

    public Long getId() {
        return id;
    }

    public GroupMember getGroupMember() {
        return groupMember;
    }

    public RoutineSchedule getRoutineSchedule() {
        return routineSchedule;
    }

    public LocalDate getScheduledDate() {
        return scheduledDate;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getInvalidatedAt() {
        return invalidatedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    private void transitionTo(VerificationStatus target, VerificationStatus... allowedSources) {
        requireTransition(target, allowedSources);
        status = target;
    }

    private void requireTransition(VerificationStatus target, VerificationStatus... allowedSources) {
        if (!Set.of(allowedSources).contains(status)) {
            throw new IllegalStateException("verification cannot transition from " + status + " to " + target);
        }
    }

    private Clock requireClock(Clock clock) {
        return Objects.requireNonNull(clock, "clock must not be null");
    }
}

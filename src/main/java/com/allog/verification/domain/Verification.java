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
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;

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
        transitionTo(VerificationStatus.SUBMITTED, VerificationStatus.PENDING_UPLOAD, VerificationStatus.RETRY_REQUIRED);
        submittedAt = LocalDateTime.now(requireClock(clock));
    }

    public void startProcessing() {
        transitionTo(VerificationStatus.PROCESSING, VerificationStatus.SUBMITTED);
    }

    public void approve(Clock clock) {
        transitionTo(VerificationStatus.APPROVED, VerificationStatus.PROCESSING, VerificationStatus.REVIEW_REQUIRED);
        approvedAt = LocalDateTime.now(requireClock(clock));
    }

    public void requestReview() {
        transitionTo(VerificationStatus.REVIEW_REQUIRED, VerificationStatus.PROCESSING);
    }

    public void requestRetry() {
        transitionTo(
                VerificationStatus.RETRY_REQUIRED,
                VerificationStatus.PROCESSING,
                VerificationStatus.REVIEW_REQUIRED
        );
    }

    public void reject() {
        transitionTo(VerificationStatus.REJECTED, VerificationStatus.PROCESSING, VerificationStatus.REVIEW_REQUIRED);
    }

    public void invalidate(Clock clock) {
        transitionTo(VerificationStatus.INVALIDATED, VerificationStatus.APPROVED);
        invalidatedAt = LocalDateTime.now(requireClock(clock));
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

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public LocalDateTime getInvalidatedAt() {
        return invalidatedAt;
    }

    private void transitionTo(VerificationStatus target, VerificationStatus... allowedSources) {
        if (!Set.of(allowedSources).contains(status)) {
            throw new IllegalStateException("verification cannot transition from " + status + " to " + target);
        }
        status = target;
    }

    private Clock requireClock(Clock clock) {
        return Objects.requireNonNull(clock, "clock must not be null");
    }
}

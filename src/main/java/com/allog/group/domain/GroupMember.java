package com.allog.group.domain;

import com.allog.common.persistence.BaseTimeEntity;
import com.allog.user.domain.User;
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

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "group_member",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_group_member_group_user",
                columnNames = {"routine_group_id", "user_id"}
        ),
        indexes = {
                @Index(name = "idx_group_member_user_status", columnList = "user_id,status"),
                @Index(
                        name = "idx_group_member_group_participation_started_at",
                        columnList = "routine_group_id,participation_started_at"
                )
        }
)
public class GroupMember extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "routine_group_id", nullable = false)
    private RoutineGroup routineGroup;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GroupMemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GroupMemberStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "participation_started_at")
    private Instant participationStartedAt;

    protected GroupMember() {
    }

    public GroupMember(
            RoutineGroup routineGroup,
            User user,
            GroupMemberRole role,
            GroupMemberStatus status,
            Instant joinedAt
    ) {
        this.routineGroup = Objects.requireNonNull(routineGroup, "routineGroup must not be null");
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public RoutineGroup getRoutineGroup() {
        return routineGroup;
    }

    public User getUser() {
        return user;
    }

    public GroupMemberRole getRole() {
        return role;
    }

    public GroupMemberStatus getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getParticipationStartedAt() {
        return participationStartedAt;
    }

    public void startParticipation(Instant activationTime) {
        Instant startedAt = Objects.requireNonNull(activationTime, "activationTime must not be null");
        if (status != GroupMemberStatus.JOINED || participationStartedAt != null) {
            throw new IllegalStateException("only a not-yet-started JOINED member can start participation");
        }
        status = GroupMemberStatus.ACTIVE;
        participationStartedAt = startedAt;
    }
}

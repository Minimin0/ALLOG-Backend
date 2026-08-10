package com.allog.group.domain;

import com.allog.common.persistence.BaseTimeEntity;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

@Entity
@Table(name = "routine_group")
public class RoutineGroup extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "routine_definition_id", nullable = false)
    private RoutineDefinition routineDefinition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GroupVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RoutineGroupStatus status;

    @Column(name = "max_members", nullable = false)
    private int maxMembers;

    @Column(name = "required_completion_count", nullable = false)
    private int requiredCompletionCount;

    protected RoutineGroup() {
    }

    public RoutineGroup(
            RoutineDefinition routineDefinition,
            User createdBy,
            String name,
            GroupVisibility visibility,
            RoutineGroupStatus status,
            int maxMembers,
            int requiredCompletionCount
    ) {
        this.routineDefinition = Objects.requireNonNull(routineDefinition, "routineDefinition must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.name = requireText(name, "name");
        this.visibility = Objects.requireNonNull(visibility, "visibility must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.maxMembers = requirePositive(maxMembers, "maxMembers");
        this.requiredCompletionCount = requirePositive(requiredCompletionCount, "requiredCompletionCount");
    }

    public Long getId() {
        return id;
    }

    public RoutineDefinition getRoutineDefinition() {
        return routineDefinition;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public String getName() {
        return name;
    }

    public GroupVisibility getVisibility() {
        return visibility;
    }

    public RoutineGroupStatus getStatus() {
        return status;
    }

    public int getMaxMembers() {
        return maxMembers;
    }

    public int getRequiredCompletionCount() {
        return requiredCompletionCount;
    }

    public boolean canActivate() {
        return status == RoutineGroupStatus.RECRUITING || status == RoutineGroupStatus.FULL;
    }

    public void activate() {
        if (!canActivate()) {
            throw new IllegalStateException("routine group cannot activate from " + status);
        }
        status = RoutineGroupStatus.ACTIVE;
    }

    public boolean canAcceptNewMember() {
        return status == RoutineGroupStatus.RECRUITING;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}

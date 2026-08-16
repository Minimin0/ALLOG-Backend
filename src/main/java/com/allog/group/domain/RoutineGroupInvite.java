package com.allog.group.domain;

import com.allog.common.persistence.BaseTimeEntity;
import com.allog.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "routine_group_invite")
public class RoutineGroupInvite extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "routine_group_id", nullable = false, unique = true)
    private RoutineGroup routineGroup;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    protected RoutineGroupInvite() {
    }

    public RoutineGroupInvite(RoutineGroup routineGroup, User createdBy, String code) {
        this.routineGroup = Objects.requireNonNull(routineGroup, "routineGroup must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public RoutineGroup getRoutineGroup() {
        return routineGroup;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public String getCode() {
        return code;
    }
}

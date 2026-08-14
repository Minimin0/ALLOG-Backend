package com.allog.routine.domain;

import com.allog.common.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "routine_definition")
public class RoutineDefinition extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "routine_key", length = RoutineKey.MAX_LENGTH, unique = true)
    private String routineKey;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 1000)
    private String description;

    protected RoutineDefinition() {
    }

    public RoutineDefinition(String name, String description) {
        this(null, name, description);
    }

    public RoutineDefinition(RoutineKey routineKey, String name, String description) {
        this.routineKey = routineKey == null ? null : routineKey.value();
        this.name = requireText(name, "name");
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public RoutineKey getRoutineKey() {
        return routineKey == null ? null : new RoutineKey(routineKey);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}

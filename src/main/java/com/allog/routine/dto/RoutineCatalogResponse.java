package com.allog.routine.dto;

import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineKey;
import java.util.List;
import java.util.Objects;

public record RoutineCatalogResponse(List<Item> items) {

    public RoutineCatalogResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }

    /**
     * {@code routineKey} is the stable identifier a client may match on; {@code routineDefinitionId}
     * is what group creation takes, and is not stable across environments.
     */
    public record Item(Long routineDefinitionId, String routineKey, String name, String description) {

        public static Item from(RoutineDefinition definition) {
            RoutineKey key = definition.getRoutineKey();
            return new Item(
                    definition.getId(),
                    key == null ? null : key.value(),
                    definition.getName(),
                    definition.getDescription()
            );
        }
    }
}

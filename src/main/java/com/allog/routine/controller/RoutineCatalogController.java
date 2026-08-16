package com.allog.routine.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.routine.dto.RoutineCatalogResponse;
import com.allog.routine.repository.RoutineDefinitionRepository;
import java.util.Objects;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routine catalogue a client picks from before creating a group. Without it a client has no way
 * to learn a valid {@code routineDefinitionId}, which {@code POST /api/v1/me/groups} requires.
 */
@RestController
@RequestMapping("/api/v1/routines")
public class RoutineCatalogController {

    private final RoutineDefinitionRepository repository;

    public RoutineCatalogController(RoutineDefinitionRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @GetMapping
    public RoutineCatalogResponse list(@AuthenticationPrincipal AllogPrincipal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        return new RoutineCatalogResponse(
                repository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                        .map(RoutineCatalogResponse.Item::from)
                        .toList()
        );
    }
}

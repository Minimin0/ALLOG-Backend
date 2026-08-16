package com.allog.group.repository;

import com.allog.group.domain.RoutineGroupInvite;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineGroupInviteRepository extends JpaRepository<RoutineGroupInvite, Long> {

    Optional<RoutineGroupInvite> findByRoutineGroup_Id(Long routineGroupId);

    @EntityGraph(attributePaths = "routineGroup")
    Optional<RoutineGroupInvite> findByCode(String code);

    boolean existsByCode(String code);
}

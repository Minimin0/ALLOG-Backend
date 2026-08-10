package com.allog.group.repository;

import com.allog.group.domain.RoutineGroup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RoutineGroupRepository extends JpaRepository<RoutineGroup, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select routineGroup from RoutineGroup routineGroup where routineGroup.id = :groupId")
    Optional<RoutineGroup> findByIdForUpdate(@Param("groupId") Long groupId);
}

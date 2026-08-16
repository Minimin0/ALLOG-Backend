package com.allog.group.repository;

import com.allog.group.domain.RoutineGroup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface RoutineGroupRepository extends JpaRepository<RoutineGroup, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select routineGroup from RoutineGroup routineGroup where routineGroup.id = :groupId")
    Optional<RoutineGroup> findByIdForUpdate(@Param("groupId") Long groupId);

    /**
     * Ids of groups whose lifecycle can still move, walked by id so later pages are reached instead
     * of the first batch being re-read forever. Terminal groups are excluded - they never change
     * again, and scanning them would grow the sweep for nothing.
     */
    @Query("""
            select routineGroup.id
              from RoutineGroup routineGroup
             where routineGroup.status in (
                       com.allog.group.domain.RoutineGroupStatus.RECRUITING,
                       com.allog.group.domain.RoutineGroupStatus.FULL,
                       com.allog.group.domain.RoutineGroupStatus.ACTIVE)
               and routineGroup.id > :afterId
             order by routineGroup.id asc
            """)
    List<Long> findReconcilableIdsAfter(@Param("afterId") Long afterId, Pageable pageable);
}

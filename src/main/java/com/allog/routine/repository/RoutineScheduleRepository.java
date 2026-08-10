package com.allog.routine.repository;

import com.allog.routine.domain.RoutineSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoutineScheduleRepository extends JpaRepository<RoutineSchedule, Long> {

    Optional<RoutineSchedule> findByRoutineGroup_Id(Long routineGroupId);
}

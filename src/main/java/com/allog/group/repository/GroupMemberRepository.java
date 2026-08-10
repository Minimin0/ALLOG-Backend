package com.allog.group.repository;

import com.allog.group.domain.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    Optional<GroupMember> findByRoutineGroup_IdAndUser_Id(Long routineGroupId, Long userId);

    List<GroupMember> findAllByRoutineGroup_Id(Long routineGroupId);

    List<GroupMember> findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(Long routineGroupId);
}

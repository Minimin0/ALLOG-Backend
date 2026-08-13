package com.allog.group.repository;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    @EntityGraph(attributePaths = "routineGroup")
    Optional<GroupMember> findByRoutineGroup_IdAndUser_Id(Long routineGroupId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "routineGroup")
    @Query("""
            select member
            from GroupMember member
            where member.routineGroup.id = :routineGroupId
              and member.user.id = :userId
            """)
    Optional<GroupMember> findByRoutineGroup_IdAndUser_IdForUpdate(
            @Param("routineGroupId") Long routineGroupId,
            @Param("userId") Long userId
    );

    @EntityGraph(attributePaths = {"routineGroup", "routineGroup.routineDefinition"})
    Optional<GroupMember> findByRoutineGroup_IdAndUser_IdAndStatusIn(
            Long routineGroupId,
            Long userId,
            Collection<GroupMemberStatus> statuses
    );

    List<GroupMember> findAllByRoutineGroup_Id(Long routineGroupId);

    @EntityGraph(attributePaths = "routineGroup")
    List<GroupMember> findAllByRoutineGroup_IdAndParticipationStartedAtIsNotNull(Long routineGroupId);

    @EntityGraph(attributePaths = {"routineGroup", "routineGroup.routineDefinition"})
    Slice<GroupMember> findAllByUser_IdAndStatusIn(
            Long userId,
            Collection<GroupMemberStatus> statuses,
            Pageable pageable
    );
}

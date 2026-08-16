package com.allog.group.service;

import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.group.repository.RoutineGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Moves a single group to wherever its schedule says it should be by now.
 *
 * <p>Groups end on time whether or not anyone opens the app, and read endpoints stay read-only: a
 * {@code GET} must never be what expires someone's group.
 *
 * <p>One group, one transaction, behind the same group lock everything else uses. Two application
 * instances reconciling the same group is therefore safe - the second one takes the lock after the
 * first commits, re-reads the status, and finds nothing left to do.
 */
@Service
public class RoutineGroupLifecycleReconciler {

    private final RoutineGroupRepository routineGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final RoutineGroupActivationService activationService;
    private final MembershipLifecycleService membershipLifecycleService;
    private final GroupFinalizationService finalizationService;
    private final Clock clock;

    public RoutineGroupLifecycleReconciler(
            RoutineGroupRepository routineGroupRepository,
            GroupMemberRepository groupMemberRepository,
            RoutineGroupActivationService activationService,
            MembershipLifecycleService membershipLifecycleService,
            GroupFinalizationService finalizationService,
            Clock clock
    ) {
        this.routineGroupRepository = Objects.requireNonNull(routineGroupRepository);
        this.groupMemberRepository = Objects.requireNonNull(groupMemberRepository);
        this.activationService = Objects.requireNonNull(activationService);
        this.membershipLifecycleService = Objects.requireNonNull(membershipLifecycleService);
        this.finalizationService = Objects.requireNonNull(finalizationService);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public void reconcile(Long groupId) {
        Objects.requireNonNull(groupId, "groupId must not be null");

        RoutineGroup group = routineGroupRepository.findByIdForUpdate(groupId).orElse(null);
        if (group == null) {
            return;
        }
        // One reading of the clock per group, so every boundary in this decision agrees.
        Instant now = clock.instant();
        Clock snapshotClock = Clock.fixed(now, clock.getZone());

        switch (group.getStatus()) {
            case RECRUITING, FULL -> reconcileBeforeStart(group, now);
            case ACTIVE -> finalizationService.finalizeIfReady(group, snapshotClock);
            default -> {
                // Already settled by someone else, or never started. Nothing to do.
            }
        }
    }

    /**
     * A group that has not started either fills up and begins, or runs out of schedule and expires.
     * There is no recruiting timeout: the schedule decides, so a group is only dead once it could no
     * longer reach its goal even by starting right now.
     */
    private void reconcileBeforeStart(RoutineGroup group, Instant now) {
        long joined = groupMemberRepository
                .countByRoutineGroup_IdAndStatus(group.getId(), GroupMemberStatus.JOINED);
        if (joined > group.getMaxMembers()) {
            throw new IllegalStateException(
                    "routine group holds more members than its capacity: " + group.getId());
        }

        boolean stillReachable = activationService.hasEnoughRemainingOpportunities(group, now);
        if (joined == group.getMaxMembers() && stillReachable) {
            // Normally the join that filled the room already started it; this catches a room left
            // full by an older code path or a crash between the two.
            if (group.canAcceptNewMember()) {
                group.markFull();
            }
            activationService.activateLocked(group, now);
            return;
        }
        if (!stillReachable) {
            membershipLifecycleService.removeRemainingMembers(group.getId());
            group.expireBeforeStart();
            return;
        }
        if (!group.canAcceptNewMember()) {
            // Marked full but no longer at capacity - someone left. Let it recruit again.
            group.reopenRecruitingAfterDeparture();
        }
    }
}

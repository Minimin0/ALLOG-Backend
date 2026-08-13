package com.allog.verification.service;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.group.repository.GroupMemberRepository;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.repository.RoutineScheduleRepository;
import com.allog.routine.schedule.RoutineScheduleCalculator;
import com.allog.verification.analysis.domain.VerificationAnalysis;
import com.allog.verification.analysis.repository.VerificationAnalysisRepository;
import com.allog.verification.analysis.service.AnalysisRequestIdGenerator;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationMedia;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.repository.VerificationMediaRepository;
import com.allog.verification.repository.VerificationRepository;
import com.allog.verification.storage.VerificationMediaStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

@Service
public class VerificationCommandService {

    private final GroupMemberRepository groupMemberRepository;
    private final RoutineScheduleRepository routineScheduleRepository;
    private final VerificationRepository verificationRepository;
    private final VerificationMediaRepository verificationMediaRepository;
    private final VerificationAnalysisRepository verificationAnalysisRepository;
    private final AnalysisRequestIdGenerator analysisRequestIdGenerator;
    private final VerificationCreator verificationCreator;
    private final VerificationMediaPolicy mediaPolicy;
    private final Clock clock;
    private final RoutineScheduleCalculator scheduleCalculator = new RoutineScheduleCalculator();

    public VerificationCommandService(
            GroupMemberRepository groupMemberRepository,
            RoutineScheduleRepository routineScheduleRepository,
            VerificationRepository verificationRepository,
            VerificationMediaRepository verificationMediaRepository,
            VerificationAnalysisRepository verificationAnalysisRepository,
            AnalysisRequestIdGenerator analysisRequestIdGenerator,
            VerificationCreator verificationCreator,
            VerificationMediaPolicy mediaPolicy,
            Clock clock
    ) {
        this.groupMemberRepository = Objects.requireNonNull(groupMemberRepository);
        this.routineScheduleRepository = Objects.requireNonNull(routineScheduleRepository);
        this.verificationRepository = Objects.requireNonNull(verificationRepository);
        this.verificationMediaRepository = Objects.requireNonNull(verificationMediaRepository);
        this.verificationAnalysisRepository = Objects.requireNonNull(verificationAnalysisRepository);
        this.analysisRequestIdGenerator = Objects.requireNonNull(analysisRequestIdGenerator);
        this.verificationCreator = Objects.requireNonNull(verificationCreator);
        this.mediaPolicy = Objects.requireNonNull(mediaPolicy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public Verification createOrGetCurrent(Long groupId, Long currentUserId) {
        GroupMember member = activeMember(groupId, currentUserId);
        RoutineSchedule schedule = schedule(groupId);
        Instant snapshotNow = clock.instant();
        LocalDate currentDate = currentDate(schedule, snapshotNow);
        Instant deadline = eligibleDeadline(member, schedule, currentDate);

        return verificationRepository
                .findByGroupMember_IdAndRoutineSchedule_IdAndScheduledDate(
                        member.getId(), schedule.getId(), currentDate
                )
                .orElseGet(() -> {
                    requireBeforeDeadline(snapshotNow, deadline);
                    return verificationCreator.create(member, schedule, currentDate);
                });
    }

    @Transactional
    public VerificationCurrentResult createOrGetCurrentResult(Long groupId, Long currentUserId) {
        Verification verification = createOrGetCurrent(groupId, currentUserId);
        Instant deadline = eligibleDeadline(
                verification.getGroupMember(),
                verification.getRoutineSchedule(),
                verification.getScheduledDate()
        );
        return VerificationCurrentResult.from(verification, deadline);
    }

    @Transactional
    UploadTarget prepareCurrentUpload(
            Long groupId,
            Long currentUserId,
            String contentType,
            long expectedSizeBytes
    ) {
        mediaPolicy.requireEnabled();
        String allowedContentType = mediaPolicy.requireAllowedContentType(contentType);
        mediaPolicy.requireAllowedSize(expectedSizeBytes);
        Verification verification = createOrGetCurrent(groupId, currentUserId);
        Instant snapshotNow = clock.instant();
        RoutineSchedule schedule = verification.getRoutineSchedule();
        Instant deadline = eligibleDeadline(
                verification.getGroupMember(),
                schedule,
                verification.getScheduledDate()
        );
        requireBeforeDeadline(snapshotNow, deadline);
        if (verification.getStatus() != VerificationStatus.PENDING_UPLOAD) {
            if (verification.getStatus() == VerificationStatus.RETRY_REQUIRED) {
                throw new VerificationCommandConflictException("user retry is not enabled");
            }
            throw new VerificationCommandConflictException("only PENDING_UPLOAD verification can issue upload grants");
        }

        VerificationMedia media = verificationMediaRepository.findByVerification_Id(verification.getId())
                .orElseGet(() -> verificationMediaRepository.save(VerificationMedia.create(
                        verification,
                        "verification-media/" + UUID.randomUUID(),
                        allowedContentType,
                        expectedSizeBytes
                )));
        if (!media.getContentType().equals(allowedContentType)
                || media.getExpectedSizeBytes() != expectedSizeBytes) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.METADATA_CONFLICT,
                    "current verification upload metadata cannot be changed"
            );
        }
        if (media.isConfirmed()) {
            throw new IllegalStateException("PENDING_UPLOAD verification cannot have confirmed media");
        }

        Instant configuredExpiry = snapshotNow.plus(mediaPolicy.uploadExpiry());
        return new UploadTarget(
                media.getObjectKey(),
                media.getContentType(),
                media.getExpectedSizeBytes(),
                configuredExpiry.isBefore(deadline) ? configuredExpiry : deadline
        );
    }

    @Transactional
    SubmissionTarget prepareCurrentSubmission(Long groupId, Long currentUserId) {
        mediaPolicy.requireEnabled();
        CurrentVerification current = currentForUpdate(groupId, currentUserId);
        Verification verification = current.verification();
        VerificationMedia media = verificationMediaRepository
                .findByVerification_Id(verification.getId())
                .orElse(null);

        if (verification.getStatus() == VerificationStatus.PENDING_UPLOAD) {
            requireInitialSubmissionWindow(current);
            if (media == null) {
                throw new VerificationMediaCommandException(
                        VerificationMediaCommandException.Reason.MEDIA_NOT_BOUND,
                        "current verification has no media binding"
                );
            }
            if (media.isConfirmed()) {
                throw new IllegalStateException("PENDING_UPLOAD verification cannot have confirmed media");
            }
            return SubmissionTarget.pending(media);
        }
        if (verification.getStatus() == VerificationStatus.RETRY_REQUIRED) {
            throw new VerificationCommandConflictException("user retry is not enabled");
        }
        requireConfirmedMedia(media);
        return SubmissionTarget.idempotent(verification);
    }

    @Transactional
    Verification submitInspectedCurrent(
            Long groupId,
            Long currentUserId,
            VerificationMediaStorage.StoredMediaInspection inspection
    ) {
        mediaPolicy.requireEnabled();
        CurrentVerification current = currentForUpdate(groupId, currentUserId);
        Verification verification = current.verification();
        VerificationMedia media = verificationMediaRepository
                .findByVerificationIdForUpdate(verification.getId())
                .orElse(null);

        if (verification.getStatus() != VerificationStatus.PENDING_UPLOAD) {
            if (verification.getStatus() == VerificationStatus.RETRY_REQUIRED) {
                throw new VerificationCommandConflictException("user retry is not enabled");
            }
            requireConfirmedMedia(media);
            return verification;
        }

        requireInitialSubmissionWindow(current);
        if (media == null) {
            throw new VerificationMediaCommandException(
                    VerificationMediaCommandException.Reason.MEDIA_NOT_BOUND,
                    "current verification has no media binding"
            );
        }
        if (media.isConfirmed()) {
            throw new IllegalStateException("PENDING_UPLOAD verification cannot have confirmed media");
        }
        mediaPolicy.requireInspection(
                media.getObjectKey(),
                media.getContentType(),
                media.getExpectedSizeBytes(),
                inspection
        );

        Clock eventClock = Clock.fixed(current.snapshotNow(), clock.getZone());
        media.confirm(inspection.contentLength(), eventClock);
        verification.submit(eventClock);
        verificationAnalysisRepository.save(VerificationAnalysis.createPending(
                verification,
                analysisRequestIdGenerator.generate()
        ));
        return verification;
    }

    private CurrentVerification currentForUpdate(Long groupId, Long currentUserId) {
        GroupMember member = activeMember(groupId, currentUserId);
        RoutineSchedule schedule = schedule(groupId);
        Instant snapshotNow = clock.instant();
        LocalDate currentDate = currentDate(schedule, snapshotNow);
        Instant deadline = eligibleDeadline(member, schedule, currentDate);
        Verification verification = verificationRepository.findCurrentForUpdate(
                        member.getId(), schedule.getId(), currentDate
                )
                .orElseThrow(() -> new VerificationCommandConflictException(
                        "current verification upload slot does not exist"
                ));
        return new CurrentVerification(member, verification, snapshotNow, deadline);
    }

    private void requireInitialSubmissionWindow(CurrentVerification current) {
        if (current.snapshotNow().isBefore(current.member().getParticipationStartedAt())) {
            throw new VerificationCommandConflictException("participation has not started");
        }
        requireBeforeDeadline(current.snapshotNow(), current.deadline());
    }

    private void requireConfirmedMedia(VerificationMedia media) {
        if (media == null || !media.isConfirmed()) {
            throw new IllegalStateException("submitted verification requires confirmed media");
        }
    }

    private GroupMember activeMember(Long groupId, Long currentUserId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(currentUserId, "currentUserId must not be null");
        GroupMember member = groupMemberRepository
                .findByRoutineGroup_IdAndUser_IdForUpdate(groupId, currentUserId)
                .orElseThrow(VerificationMembershipNotFoundException::new);
        GroupMemberStatus status = member.getStatus();
        if (status == GroupMemberStatus.LEFT || status == GroupMemberStatus.REMOVED) {
            throw new VerificationMembershipNotFoundException();
        }
        if (status != GroupMemberStatus.ACTIVE) {
            throw new VerificationCommandConflictException("only ACTIVE members can verify");
        }
        if (member.getRoutineGroup().getStatus() != RoutineGroupStatus.ACTIVE) {
            throw new IllegalStateException("ACTIVE member requires an ACTIVE group");
        }
        if (member.getParticipationStartedAt() == null) {
            throw new IllegalStateException("ACTIVE member requires participationStartedAt");
        }
        return member;
    }

    private RoutineSchedule schedule(Long groupId) {
        return routineScheduleRepository.findByRoutineGroup_Id(groupId)
                .orElseThrow(() -> new IllegalStateException("routine schedule not found for group: " + groupId));
    }

    private LocalDate currentDate(RoutineSchedule schedule, Instant snapshotNow) {
        return LocalDate.ofInstant(snapshotNow, ZoneId.of(schedule.getTimezone()));
    }

    private Instant eligibleDeadline(
            GroupMember member,
            RoutineSchedule schedule,
            LocalDate currentDate
    ) {
        Instant deadline = scheduleCalculator.deadlineFor(schedule, currentDate)
                .orElseThrow(() -> new VerificationCommandConflictException(
                        "there is no scheduled verification opportunity today"
                ));
        if (!member.getParticipationStartedAt().isBefore(deadline)) {
            throw new VerificationCommandConflictException(
                    "current opportunity is outside the participation boundary"
            );
        }
        return deadline;
    }

    private void requireBeforeDeadline(Instant snapshotNow, Instant deadline) {
        if (!snapshotNow.isBefore(deadline)) {
            throw new VerificationCommandConflictException("current verification deadline has closed");
        }
    }

    record UploadTarget(
            String objectKey,
            String contentType,
            long expectedSizeBytes,
            Instant expiresAt
    ) {
    }

    record SubmissionTarget(
            String objectKey,
            String contentType,
            long expectedSizeBytes,
            Verification idempotentResult
    ) {
        static SubmissionTarget pending(VerificationMedia media) {
            return new SubmissionTarget(
                    media.getObjectKey(),
                    media.getContentType(),
                    media.getExpectedSizeBytes(),
                    null
            );
        }

        static SubmissionTarget idempotent(Verification verification) {
            return new SubmissionTarget(null, null, 0, verification);
        }
    }

    private record CurrentVerification(
            GroupMember member,
            Verification verification,
            Instant snapshotNow,
            Instant deadline
    ) {
    }
}

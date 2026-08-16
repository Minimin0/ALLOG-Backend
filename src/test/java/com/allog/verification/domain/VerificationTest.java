package com.allog.verification.domain;

import com.allog.group.domain.GroupMember;
import com.allog.group.domain.GroupMemberRole;
import com.allog.group.domain.GroupMemberStatus;
import com.allog.group.domain.GroupVisibility;
import com.allog.group.domain.RoutineGroup;
import com.allog.group.domain.RoutineGroupStatus;
import com.allog.routine.domain.RoutineDefinition;
import com.allog.routine.domain.RoutineSchedule;
import com.allog.routine.domain.ScheduleType;
import com.allog.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationTest {

    private static final Clock FIRST = Clock.fixed(
            Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC
    );
    private static final Clock SECOND = Clock.fixed(
            Instant.parse("2026-08-11T11:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void startsAsBackendOwnedPendingUpload() {
        Verification verification = verification();

        assertAll(
                () -> assertEquals(VerificationStatus.PENDING_UPLOAD, verification.getStatus()),
                () -> assertNull(verification.getSubmittedAt()),
                () -> assertNull(verification.getApprovedAt()),
                () -> assertNull(verification.getInvalidatedAt())
        );
    }

    @Test
    void submitsProcessesAndApproves() {
        Verification verification = verification();

        verification.submit(FIRST);
        verification.startProcessing();
        verification.approve(SECOND);

        assertAll(
                () -> assertEquals(VerificationStatus.APPROVED, verification.getStatus()),
                () -> assertEquals(FIRST.instant(), verification.getSubmittedAt()),
                () -> assertEquals(SECOND.instant(), verification.getApprovedAt()),
                () -> assertTrue(verification.getStatus().countsAsProgress())
        );
    }

    @Test
    void submittedApprovesWithoutPassingThroughProcessing() {
        Verification verification = verification();
        verification.submit(FIRST);

        verification.approve(SECOND);

        assertAll(
                () -> assertEquals(VerificationStatus.APPROVED, verification.getStatus()),
                () -> assertEquals(SECOND.instant(), verification.getApprovedAt()),
                () -> assertTrue(verification.getStatus().countsAsProgress())
        );
    }

    @Test
    void spendsExactlyOneGuidedRetry() {
        Verification verification = verification();
        verification.submit(FIRST);

        assertTrue(verification.hasRetryRemaining());
        verification.requestRetry();
        verification.submit(SECOND);

        assertAll(
                () -> assertEquals(2, verification.getAttemptCount()),
                () -> assertFalse(verification.hasRetryRemaining()),
                // the retry is spent, so the verification can never be handed back to the member again
                () -> assertThrows(IllegalStateException.class, verification::requestRetry)
        );
    }

    /** A second rejection holds the verification without ever passing through PROCESSING. */
    @Test
    void submittedCanBeHeldForReview() {
        Verification verification = verification();
        verification.submit(FIRST);

        verification.requestReview();

        assertEquals(VerificationStatus.REVIEW_REQUIRED, verification.getStatus());
    }

    @Test
    void eventTimestampDoesNotDependOnClockZone() {
        Instant eventTime = Instant.parse("2026-08-11T15:30:00.123456Z");
        Verification utc = verification();
        Verification seoul = verification();

        utc.submit(Clock.fixed(eventTime, ZoneOffset.UTC));
        seoul.submit(Clock.fixed(eventTime, ZoneId.of("Asia/Seoul")));

        assertAll(
                () -> assertEquals(eventTime, utc.getSubmittedAt()),
                () -> assertEquals(eventTime, seoul.getSubmittedAt())
        );
    }

    @Test
    void processingCanRequestReviewThenApprove() {
        Verification verification = processing();

        verification.requestReview();
        verification.approve(SECOND);

        assertEquals(VerificationStatus.APPROVED, verification.getStatus());
    }

    @Test
    void processingCanRequestRetryAndResubmit() {
        Verification verification = processing();

        verification.requestRetry();
        verification.submit(SECOND);

        assertAll(
                () -> assertEquals(VerificationStatus.SUBMITTED, verification.getStatus()),
                () -> assertEquals(SECOND.instant(), verification.getSubmittedAt())
        );
    }

    @Test
    void reviewCanRequestRetryOrReject() {
        Verification retry = processing();
        retry.requestReview();
        retry.requestRetry();
        Verification rejected = processing();
        rejected.requestReview();
        rejected.rejectByOperator(SECOND, 99L, "operator note");

        assertAll(
                () -> assertEquals(VerificationStatus.RETRY_REQUIRED, retry.getStatus()),
                () -> assertEquals(VerificationStatus.REJECTED, rejected.getStatus())
        );
    }

    @Test
    void processingCanReject() {
        Verification verification = processing();

        verification.rejectByOperator(SECOND, 99L, "operator note");

        assertEquals(VerificationStatus.REJECTED, verification.getStatus());
    }

    @Test
    void approvedCanBeInvalidated() {
        Verification verification = processing();
        verification.approve(FIRST);

        verification.invalidate(SECOND);

        assertAll(
                () -> assertEquals(VerificationStatus.INVALIDATED, verification.getStatus()),
                () -> assertEquals(SECOND.instant(), verification.getInvalidatedAt()),
                () -> assertFalse(verification.getStatus().countsAsProgress())
        );
    }

    @Test
    void retrySubmitCannotMoveSubmittedAtBackwards() {
        Verification verification = processing();
        verification.requestRetry();

        assertThrows(IllegalStateException.class, () -> verification.submit(Clock.fixed(
                Instant.parse("2026-08-11T09:59:59Z"), ZoneOffset.UTC
        )));
        assertAll(
                () -> assertEquals(VerificationStatus.RETRY_REQUIRED, verification.getStatus()),
                () -> assertEquals(FIRST.instant(), verification.getSubmittedAt())
        );
    }

    @Test
    void approvalCannotPrecedeSubmission() {
        Verification verification = processing();

        assertThrows(IllegalStateException.class, () -> verification.approve(Clock.fixed(
                Instant.parse("2026-08-11T09:59:59Z"), ZoneOffset.UTC
        )));
        assertAll(
                () -> assertEquals(VerificationStatus.PROCESSING, verification.getStatus()),
                () -> assertNull(verification.getApprovedAt())
        );
    }

    @Test
    void invalidationCannotPrecedeApproval() {
        Verification verification = processing();
        verification.approve(SECOND);

        assertThrows(IllegalStateException.class, () -> verification.invalidate(FIRST));
        assertAll(
                () -> assertEquals(VerificationStatus.APPROVED, verification.getStatus()),
                () -> assertNull(verification.getInvalidatedAt())
        );
    }

    @Test
    void lateApprovalIsAllowedWhenEventOrderingIsValid() {
        Verification verification = processing();
        Clock afterOpportunityDeadline = Clock.fixed(
                Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC
        );

        verification.approve(afterOpportunityDeadline);

        assertEquals(afterOpportunityDeadline.instant(), verification.getApprovedAt());
    }

    @Test
    void blocksPendingUploadToApproved() {
        assertThrows(IllegalStateException.class, () -> verification().approve(FIRST));
    }

    @Test
    void blocksRejectedToApproved() {
        Verification verification = processing();
        verification.rejectByOperator(SECOND, 99L, "operator note");

        assertThrows(IllegalStateException.class, () -> verification.approve(FIRST));
    }

    @Test
    void blocksInvalidatedToApproved() {
        Verification verification = processing();
        verification.approve(FIRST);
        verification.invalidate(SECOND);

        assertThrows(IllegalStateException.class, () -> verification.approve(FIRST));
    }

    @Test
    void onlyApprovedCountsAsProgress() {
        for (VerificationStatus status : VerificationStatus.values()) {
            assertEquals(status == VerificationStatus.APPROVED, status.countsAsProgress());
        }
    }

    private Verification processing() {
        Verification verification = verification();
        verification.submit(FIRST);
        verification.startProcessing();
        return verification;
    }

    private Verification verification() {
        User user = User.create();
        RoutineGroup group = group(user);
        RoutineSchedule schedule = schedule(group);
        GroupMember member = new GroupMember(
                group,
                user,
                GroupMemberRole.OWNER,
                GroupMemberStatus.ACTIVE,
                Instant.parse("2026-08-01T09:00:00Z")
        );
        Verification verification = Verification.create(member, schedule, LocalDate.of(2026, 8, 11));
        assertNotNull(verification);
        return verification;
    }

    private RoutineGroup group(User user) {
        return new RoutineGroup(
                new RoutineDefinition("물 마시기", null),
                user,
                "건강한 물 마시기",
                GroupVisibility.PUBLIC,
                RoutineGroupStatus.ACTIVE,
                5,
                3
        );
    }

    private RoutineSchedule schedule(RoutineGroup group) {
        return new RoutineSchedule(
                group,
                ScheduleType.DAILY,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 16),
                LocalTime.of(23, 0),
                "Asia/Seoul",
                Set.of()
        );
    }
}

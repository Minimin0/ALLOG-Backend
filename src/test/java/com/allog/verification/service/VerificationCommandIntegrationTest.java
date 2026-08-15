package com.allog.verification.service;

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
import com.allog.verification.analysis.domain.AnalysisRecommendation;
import com.allog.verification.analysis.domain.VerificationAnalysis;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.analysis.domain.VerificationAnalysisStatus;
import com.allog.verification.analysis.repository.VerificationAnalysisRepository;
import com.allog.verification.analysis.service.AnalysisRequestIdGenerator;
import com.allog.verification.analysis.service.VerificationAnalysisClaim;
import com.allog.verification.analysis.service.VerificationAnalysisProvider;
import com.allog.verification.analysis.service.VerificationAnalysisResultService;
import com.allog.verification.analysis.service.VerificationAnalysisSuccessResult;
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationMedia;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.repository.VerificationMediaRepository;
import com.allog.verification.repository.VerificationRepository;
import com.allog.verification.media.TestPhotos;
import com.allog.verification.storage.VerificationMediaStorage;
import com.allog.verification.template.VerificationTemplateCatalog;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=${VERIFICATION_TEST_DB_URL:jdbc:h2:mem:verification-command;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${VERIFICATION_TEST_DB_USERNAME:sa}",
        "spring.datasource.password=${VERIFICATION_TEST_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=${VERIFICATION_TEST_DB_DRIVER:org.h2.Driver}",
        "allog.verification.media.enabled=true",
        "allog.verification.media.bucket=test-bucket",
        "allog.verification.media.region=ap-northeast-2",
        "allog.verification.media.max-bytes=1000000",
        "allog.verification.media.upload-expiry=5m",
        "allog.verification.media.allowed-content-types=video/mp4,image/jpeg"
})
@ActiveProfiles("test")
@Import(VerificationCommandIntegrationTest.TestConfig.class)
class VerificationCommandIntegrationTest {

    private static final Instant SNAPSHOT_NOW = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-08-11T14:00:00Z");
    private static final byte[] GPS_TAGS = "GPSLatitude=37.5665".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    /** Photo submissions are sanitized in place, so the fixture has to be a real image. */
    private static final byte[] PHOTO = TestPhotos.jpeg(4, 4);
    private static final Long OPERATOR_ID = 99L;

    @Autowired
    private VerificationCommandService service;

    @Autowired
    private VerificationMediaUploadService mediaUploadService;

    @Autowired
    private VerificationMediaSubmissionService mediaSubmissionService;

    @Autowired
    private VerificationRepository verificationRepository;

    @Autowired
    private VerificationMediaRepository verificationMediaRepository;

    @Autowired
    private VerificationAnalysisRepository verificationAnalysisRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestMediaStorage mediaStorage;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MutableAnalysisRequestIdGenerator analysisRequestIdGenerator;

    @Autowired
    private VerificationTemplateCatalog verificationTemplateCatalog;

    @Autowired
    private VerificationAnalysisResultService analysisResultService;

    @Autowired
    private VerificationReviewService reviewService;

    @BeforeEach
    void resetMediaBoundary() {
        clock.set(SNAPSHOT_NOW);
        mediaStorage.reset();
        analysisRequestIdGenerator.reset();
    }

    @Test
    void createOrGetPersistsOneUnchangedCurrentSlot() {
        Fixture fixture = fixture();

        Verification first = service.createOrGetCurrent(fixture.groupId(), fixture.userId());
        Verification second = service.createOrGetCurrent(fixture.groupId(), fixture.userId());

        assertAll(
                () -> assertEquals(first.getId(), second.getId()),
                () -> assertEquals(1, currentRows(fixture)),
                () -> assertEquals(first.getStatus(), second.getStatus()),
                () -> assertEquals(first.getSubmittedAt(), second.getSubmittedAt())
        );
    }

    @Test
    void concurrentCreateIsSerializedWithoutLeakingUniqueViolation() throws Exception {
        Fixture fixture = fixture();

        List<Long> ids = concurrently(() -> service.createOrGetCurrent(
                fixture.groupId(), fixture.userId()
        ).getId());

        assertAll(
                () -> assertEquals(ids.get(0), ids.get(1)),
                () -> assertEquals(1, currentRows(fixture))
        );
    }

    @Test
    void concurrentSubmitIsIdempotentAndPreservesFirstTimestamp() throws Exception {
        Fixture fixture = fixture();
        service.createOrGetCurrent(fixture.groupId(), fixture.userId());
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "video/mp4", 100);

        List<Instant> submittedAt = concurrently(() -> mediaSubmissionService.submitCurrent(
                fixture.groupId(), fixture.userId()
        ).submittedAt());
        Verification verification = currentVerification(fixture);
        VerificationMedia media = verificationMediaRepository
                .findByVerification_Id(verification.getId())
                .orElseThrow();

        assertAll(
                () -> assertEquals(SNAPSHOT_NOW, submittedAt.get(0)),
                () -> assertEquals(submittedAt.get(0), submittedAt.get(1)),
                () -> assertEquals(VerificationStatus.SUBMITTED, verification.getStatus()),
                () -> assertEquals(verification.getSubmittedAt(), media.getConfirmedAt()),
                () -> assertEquals(1, analysisRows(verification.getId())),
                () -> assertEquals(VerificationAnalysisStatus.PENDING, verificationAnalysisRepository
                        .findByVerification_Id(verification.getId())
                        .orElseThrow()
                        .getStatus()),
                () -> assertEquals(SNAPSHOT_NOW, inTransaction(() -> verificationRepository
                        .findByGroupMember_IdAndRoutineSchedule_IdAndScheduledDate(
                                fixture.memberId(), fixture.scheduleId(), LocalDate.of(2026, 8, 11)
                        )
                        .orElseThrow()
                        .getSubmittedAt()))
        );
    }

    @Test
    void flywayHasExactlyV1ThroughV11() {
        assertAll(
                () -> assertEquals(11, jdbcTemplate.queryForObject(
                        "select count(*) from flyway_schema_history where success = true and version is not null",
                        Integer.class
                )),
                () -> assertEquals(1, jdbcTemplate.queryForObject(
                        "select count(*) from flyway_schema_history where version = '11'",
                        Integer.class
                ))
        );
    }

    @Test
    void reissuesSameKeyAndRejectsMetadataMutation() {
        Fixture fixture = fixture();

        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "video/mp4", 100);
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "video/mp4", 100);

        assertAll(
                () -> assertEquals(2, mediaStorage.issuedKeys().size()),
                () -> assertEquals(mediaStorage.issuedKeys().get(0), mediaStorage.issuedKeys().get(1)),
                () -> assertEquals(1, jdbcTemplate.queryForObject(
                        "select count(*) from verification_media where verification_id = ("
                                + "select id from verification where group_member_id = ? and routine_schedule_id = ?"
                                + " and scheduled_date = ?)",
                        Integer.class,
                        fixture.memberId(),
                        fixture.scheduleId(),
                        LocalDate.of(2026, 8, 11)
                ))
        );
        VerificationMediaCommandException conflict = assertThrows(
                VerificationMediaCommandException.class,
                () -> mediaUploadService.issueCurrentUpload(
                        fixture.groupId(), fixture.userId(), "image/jpeg", 100
                )
        );
        assertEquals(VerificationMediaCommandException.Reason.METADATA_CONFLICT, conflict.reason());
    }

    @Test
    void concurrentUploadIntentReturnsOneBindingAndOneKeyWithoutUniqueViolation() throws Exception {
        Fixture fixture = fixture();
        service.createOrGetCurrent(fixture.groupId(), fixture.userId());

        concurrently(() -> mediaUploadService.issueCurrentUpload(
                fixture.groupId(), fixture.userId(), "video/mp4", 100
        ));

        Verification verification = currentVerification(fixture);
        String persistedKey = verificationMediaRepository
                .findByVerification_Id(verification.getId())
                .orElseThrow()
                .getObjectKey();
        assertAll(
                () -> assertEquals(1, jdbcTemplate.queryForObject(
                        "select count(*) from verification_media where verification_id = ?",
                        Integer.class,
                        verification.getId()
                )),
                () -> assertEquals(2, mediaStorage.issuedKeys().size()),
                () -> assertTrue(mediaStorage.issuedKeys().stream().allMatch(persistedKey::equals))
        );
    }

    @Test
    void concurrentUploadIntentKeepsOneAuthoritativeMetadataBinding() throws Exception {
        Fixture fixture = fixture();
        service.createOrGetCurrent(fixture.groupId(), fixture.userId());

        List<String> outcomes = concurrently(
                () -> uploadOutcome(fixture, "video/mp4"),
                () -> uploadOutcome(fixture, "image/jpeg")
        );

        Verification verification = currentVerification(fixture);
        VerificationMedia media = verificationMediaRepository
                .findByVerification_Id(verification.getId())
                .orElseThrow();
        String winningType = outcomes.get(0).equals("SUCCESS") ? "video/mp4" : "image/jpeg";
        assertAll(
                () -> assertEquals(Set.of("SUCCESS", "METADATA_CONFLICT"), Set.copyOf(outcomes)),
                () -> assertEquals(1, jdbcTemplate.queryForObject(
                        "select count(*) from verification_media where verification_id = ?",
                        Integer.class,
                        verification.getId()
                )),
                () -> assertEquals(winningType, media.getContentType())
        );
    }

    @Test
    void uploadIntentRequiresFreshOpenDeadlineForExistingSlot() {
        Fixture fixture = fixture();
        service.createOrGetCurrent(fixture.groupId(), fixture.userId());
        clock.set(DEADLINE);

        assertThrows(
                VerificationCommandConflictException.class,
                () -> mediaUploadService.issueCurrentUpload(
                        fixture.groupId(), fixture.userId(), "video/mp4", 100
                )
        );
        assertTrue(mediaStorage.issuedKeys().isEmpty());
    }

    @Test
    void uploadGrantExpiryIsCappedByRoutineDeadline() {
        clock.set(DEADLINE.minusSeconds(120));
        Fixture fixture = fixture();

        VerificationMediaStorage.UploadGrant grant = mediaUploadService.issueCurrentUpload(
                fixture.groupId(), fixture.userId(), "video/mp4", 100
        );

        assertEquals(DEADLINE, grant.expiresAt());
        assertTrue(mediaStorage.issuedKeys().getFirst().matches("verification-media/[0-9a-f-]{36}"));
    }

    @Test
    void confirmsStoredMediaAndSubmitsAtomicallyOutsideStorageTransaction() {
        Fixture fixture = fixture();
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "video/mp4", 100);

        VerificationSubmissionResult submitted = mediaSubmissionService.submitCurrent(
                fixture.groupId(), fixture.userId()
        );
        VerificationMedia media = verificationMediaRepository
                .findByVerification_Id(submitted.verificationId())
                .orElseThrow();

        assertAll(
                () -> assertEquals(VerificationStatus.SUBMITTED, submitted.status()),
                () -> assertEquals(SNAPSHOT_NOW, submitted.submittedAt()),
                () -> assertEquals(SNAPSHOT_NOW, media.getConfirmedAt()),
                () -> assertEquals(100L, media.getConfirmedSizeBytes()),
                () -> assertEquals(1, analysisRows(submitted.verificationId())),
                () -> assertEquals(VerificationAnalysisStatus.PENDING, verificationAnalysisRepository
                        .findByVerification_Id(submitted.verificationId())
                        .orElseThrow()
                        .getStatus()),
                () -> assertNull(verificationAnalysisRepository
                        .findByVerification_Id(submitted.verificationId())
                        .orElseThrow()
                        .getCriteriaVersion()),
                () -> assertFalse(mediaStorage.issueTransactionActive()),
                () -> assertFalse(mediaStorage.inspectTransactionActive())
        );
    }

    @Test
    void pilotBoundSubmitPinsExactV1ProvenanceAndIsIdempotent() {
        Fixture fixture = fixture(true);
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);

        VerificationSubmissionResult first = mediaSubmissionService.submitCurrent(
                fixture.groupId(), fixture.userId()
        );
        VerificationSubmissionResult duplicate = mediaSubmissionService.submitCurrent(
                fixture.groupId(), fixture.userId()
        );

        assertAll(
                () -> assertEquals(VerificationStatus.SUBMITTED, first.status()),
                () -> assertEquals(first.verificationId(), duplicate.verificationId()),
                () -> assertEquals(1, analysisRows(first.verificationId())),
                () -> assertEquals(
                        VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1.storageValue(),
                        verificationAnalysisRepository.findByVerification_Id(first.verificationId())
                                .orElseThrow()
                                .getCriteriaVersion()
                )
        );
    }

    @Test
    void concurrentPilotBoundSubmitCreatesOneAnalysisWithOneExactProvenance() throws Exception {
        Fixture fixture = fixture(true);
        service.createOrGetCurrent(fixture.groupId(), fixture.userId());
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);

        List<Instant> submittedAt = concurrently(() -> mediaSubmissionService.submitCurrent(
                fixture.groupId(), fixture.userId()
        ).submittedAt());
        Verification verification = currentVerification(fixture);

        assertAll(
                () -> assertEquals(submittedAt.get(0), submittedAt.get(1)),
                () -> assertEquals(1, analysisRows(verification.getId())),
                () -> assertEquals(
                        VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1.storageValue(),
                        verificationAnalysisRepository.findByVerification_Id(verification.getId())
                                .orElseThrow()
                                .getCriteriaVersion()
                )
        );
    }

    @Test
    void pilotBoundSubmitRejectsVideoBeforeConfirmation() {
        Fixture fixture = fixture(true);
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "video/mp4", 100);

        VerificationMediaCommandException exception = assertThrows(
                VerificationMediaCommandException.class,
                () -> mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId())
        );

        assertEquals(VerificationMediaCommandException.Reason.UNSUPPORTED_CONTENT_TYPE, exception.reason());
        assertPendingUnconfirmed(fixture);
    }

    @Test
    void unknownTemplateRollsBackSubmissionAtomically() {
        Fixture fixture = fixture(true);
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);
        jdbcTemplate.update(
                "update routine_group set verification_template_key = 'UNKNOWN_TEMPLATE' where id = ?",
                fixture.groupId()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId())
        );
        assertPendingUnconfirmed(fixture);
    }

    @Test
    void templateCriteriaMismatchRollsBackSubmissionAtomically() {
        Fixture fixture = fixture(true);
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);
        jdbcTemplate.update(
                "update routine_group set verification_criteria_reference = 'other-criteria@1' where id = ?",
                fixture.groupId()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId())
        );
        assertPendingUnconfirmed(fixture);
    }

    @Test
    void deadlineCrossingAfterHeadLeavesPendingAndUnconfirmed() {
        Fixture fixture = fixture();
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "video/mp4", 100);
        mediaStorage.beforeInspect(() -> clock.set(DEADLINE));

        assertThrows(
                VerificationCommandConflictException.class,
                () -> mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId())
        );

        Verification verification = currentVerification(fixture);
        VerificationMedia media = verificationMediaRepository.findByVerification_Id(verification.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationStatus.PENDING_UPLOAD, verification.getStatus()),
                () -> assertNull(verification.getSubmittedAt()),
                () -> assertNull(media.getConfirmedAt()),
                () -> assertNull(media.getConfirmedSizeBytes()),
                () -> assertEquals(0, analysisRows(verification.getId())),
                () -> assertFalse(mediaStorage.inspectTransactionActive())
        );
    }

    @Test
    void guidedRetryIssuesAFreshUploadAndRequeuesTheSameAnalysis() {
        Fixture fixture = fixture(true);
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);
        VerificationSubmissionResult first = mediaSubmissionService.submitCurrent(
                fixture.groupId(), fixture.userId()
        );
        String firstKey = mediaStorage.issuedKeys().getLast();
        UUID firstRequestId = verificationAnalysisRepository
                .findByVerification_Id(first.verificationId()).orElseThrow().getAnalysisRequestId();

        completeCurrentAnalysis(first.verificationId(), AnalysisRecommendation.REJECT_CANDIDATE);
        assertEquals(VerificationStatus.RETRY_REQUIRED, currentVerification(fixture).getStatus());

        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);
        VerificationSubmissionResult second = mediaSubmissionService.submitCurrent(
                fixture.groupId(), fixture.userId()
        );

        Verification verification = currentVerification(fixture);
        VerificationMedia media = verificationMediaRepository
                .findByVerification_Id(second.verificationId()).orElseThrow();
        VerificationAnalysis analysis = verificationAnalysisRepository
                .findByVerification_Id(second.verificationId()).orElseThrow();
        assertAll(
                () -> assertEquals(first.verificationId(), second.verificationId()),
                () -> assertEquals(VerificationStatus.SUBMITTED, verification.getStatus()),
                () -> assertEquals(2, verification.getAttemptCount()),
                () -> assertFalse(verification.hasRetryRemaining()),
                // the retry uploads a different photo, so the binding moved to a new key
                () -> assertNotEquals(firstKey, media.getObjectKey()),
                () -> assertTrue(media.isConfirmed()),
                // one analysis row, re-queued under a new request id, judged against the same criteria
                () -> assertEquals(1, analysisRows(second.verificationId())),
                () -> assertEquals(VerificationAnalysisStatus.PENDING, analysis.getStatus()),
                () -> assertNotEquals(firstRequestId, analysis.getAnalysisRequestId()),
                () -> assertNull(analysis.getRecommendation()),
                () -> assertEquals(
                        VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1.storageValue(),
                        analysis.getCriteriaVersion()
                )
        );
    }

    @Test
    void submitWithoutAFreshUploadIsRefusedWhileRetryIsPending() {
        Fixture fixture = fixture(true);
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);
        VerificationSubmissionResult submitted = mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId());
        completeCurrentAnalysis(submitted.verificationId(), AnalysisRecommendation.REJECT_CANDIDATE);

        assertThrows(
                VerificationCommandConflictException.class,
                () -> mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId())
        );
    }

    @Test
    void operatorApprovesAVerificationTheAiHeldAndItCountsAsProgress() {
        Fixture fixture = fixture(true);
        Long verificationId = heldForReview(fixture);

        Verification approved = reviewService.approve(verificationId, OPERATOR_ID);

        Verification stored = verificationRepository.findById(verificationId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationStatus.APPROVED, approved.getStatus()),
                () -> assertTrue(approved.getStatus().countsAsProgress()),
                () -> assertNotNull(approved.getApprovedAt()),
                () -> assertEquals(VerificationStatus.APPROVED, stored.getStatus()),
                // the reward this unlocks has to be able to name the person behind it
                () -> assertEquals(OPERATOR_ID, stored.getReviewedBy()),
                () -> assertEquals(SNAPSHOT_NOW, stored.getReviewedAt())
        );
    }

    @Test
    void operatorRejectsWithAReasonThatSurvivesTheTransaction() {
        Fixture fixture = fixture(true);
        Long verificationId = heldForReview(fixture);

        reviewService.reject(verificationId, OPERATOR_ID, "food is not visible in the photo");

        Verification rejected = verificationRepository.findById(verificationId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationStatus.REJECTED, rejected.getStatus()),
                () -> assertEquals("food is not visible in the photo", rejected.getReviewNote()),
                () -> assertFalse(rejected.getStatus().countsAsProgress()),
                () -> assertEquals(OPERATOR_ID, rejected.getReviewedBy()),
                () -> assertEquals(SNAPSHOT_NOW, rejected.getReviewedAt())
        );
    }

    /** An AI approval is nobody's manual decision, so the audit columns must stay empty. */
    @Test
    void anAiApprovalLeavesTheOperatorAuditEmpty() {
        Fixture fixture = fixture(true);
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);
        Long verificationId = mediaSubmissionService
                .submitCurrent(fixture.groupId(), fixture.userId())
                .verificationId();

        completeCurrentAnalysis(verificationId, AnalysisRecommendation.PASS);

        Verification approved = verificationRepository.findById(verificationId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationStatus.APPROVED, approved.getStatus()),
                () -> assertNotNull(approved.getApprovedAt()),
                () -> assertNull(approved.getReviewedBy()),
                () -> assertNull(approved.getReviewedAt()),
                () -> assertNull(approved.getReviewNote())
        );
    }

    @Test
    void anAlreadySettledVerificationCannotBeSettledAgain() {
        Fixture fixture = fixture(true);
        Long verificationId = heldForReview(fixture);
        reviewService.approve(verificationId, OPERATOR_ID);

        assertAll(
                () -> assertThrows(
                        VerificationCommandConflictException.class,
                        () -> reviewService.approve(verificationId, OPERATOR_ID)
                ),
                () -> assertThrows(
                        VerificationCommandConflictException.class,
                        () -> reviewService.reject(verificationId, OPERATOR_ID, "changed my mind")
                ),
                () -> assertEquals(
                        VerificationStatus.APPROVED,
                        verificationRepository.findById(verificationId).orElseThrow().getStatus()
                )
        );
    }

    @Test
    void reviewQueueReturnsOnlyHeldVerificationsOldestFirstWithTheirEvidence() {
        Long older = heldForReview(fixture(true));
        Long newer = heldForReview(fixture(true));
        // settled and in-flight verifications must not appear in the queue
        Long approved = heldForReview(fixture(true));
        reviewService.approve(approved, OPERATOR_ID);
        Fixture submittedOnly = fixture(true);
        mediaUploadService.issueCurrentUpload(
                submittedOnly.groupId(), submittedOnly.userId(), "image/jpeg", PHOTO.length
        );
        Long submitted = mediaSubmissionService
                .submitCurrent(submittedOnly.groupId(), submittedOnly.userId())
                .verificationId();

        List<PendingReview> queue = reviewService.reviewQueue();

        List<Long> queuedIds = queue.stream().map(PendingReview::verificationId).toList();
        PendingReview held = queue.stream()
                .filter(item -> item.verificationId().equals(older))
                .findFirst()
                .orElseThrow();
        assertAll(
                () -> assertTrue(queuedIds.containsAll(List.of(older, newer))),
                () -> assertTrue(queuedIds.indexOf(older) < queuedIds.indexOf(newer), "oldest first"),
                () -> assertFalse(queuedIds.contains(approved)),
                () -> assertFalse(queuedIds.contains(submitted)),
                // the operator gets the AI's own feedback plus something they can actually open
                () -> assertEquals(AnalysisRecommendation.REJECT_CANDIDATE, held.recommendation()),
                () -> assertEquals("OBSERVATION_COMPLETE", held.reasonCode()),
                () -> assertEquals(false, held.objectPresence()),
                () -> assertEquals(
                        VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1.storageValue(),
                        held.criteriaVersion()
                ),
                () -> assertEquals(2, held.attemptCount()),
                () -> assertNotNull(held.mediaUrl()),
                () -> assertNotNull(held.userId()),
                () -> assertNotNull(held.groupId())
        );
    }

    /** Spends the initial submission and the guided retry, so the AI puts the verification on hold. */
    private Long heldForReview(Fixture fixture) {
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);
        Long verificationId = mediaSubmissionService
                .submitCurrent(fixture.groupId(), fixture.userId())
                .verificationId();
        completeCurrentAnalysis(verificationId, AnalysisRecommendation.REJECT_CANDIDATE);

        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);
        mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId());
        completeCurrentAnalysis(verificationId, AnalysisRecommendation.REJECT_CANDIDATE);

        assertEquals(
                VerificationStatus.REVIEW_REQUIRED,
                verificationRepository.findById(verificationId).orElseThrow().getStatus()
        );
        return verificationId;
    }

    /**
     * Rejects one specific analysis. This class shares its database across tests, so claiming "the next
     * pending" would happily pick up a leftover from another test.
     */
    private void completeCurrentAnalysis(Long verificationId, AnalysisRecommendation recommendation) {
        inTransaction(() -> {
            verificationAnalysisRepository.findByVerification_Id(verificationId)
                    .orElseThrow()
                    .startProcessing(clock.instant());
            return null;
        });
        VerificationAnalysis analysis = verificationAnalysisRepository
                .findByVerification_Id(verificationId).orElseThrow();
        VerificationAnalysisClaim claim = new VerificationAnalysisClaim(
                analysis.getId(),
                analysis.getAnalysisRequestId(),
                analysis.getAttemptCount()
        );
        boolean present = recommendation == AnalysisRecommendation.PASS;
        assertTrue(analysisResultService.completeSuccess(claim, new VerificationAnalysisSuccessResult(
                recommendation,
                VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1,
                new VerificationAnalysisProvider.Result(
                        "synthetic-model",
                        new VerificationAnalysisObservation(
                                present,
                                new BigDecimal("0.7500"),
                                false,
                                true,
                                VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE
                        )
                )
        )));
    }

    @Test
    void submitOverwritesTheStoredPhotoWithItsSanitizedBytes() {
        Fixture fixture = fixture(true);
        byte[] tagged = TestPhotos.withApp1(PHOTO, GPS_TAGS);
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", tagged.length);
        mediaStorage.stageContent(tagged);

        VerificationSubmissionResult submitted = mediaSubmissionService.submitCurrent(
                fixture.groupId(), fixture.userId()
        );

        VerificationMedia media = verificationMediaRepository
                .findByVerification_Id(submitted.verificationId())
                .orElseThrow();
        byte[] stored = mediaStorage.storedContent();
        assertAll(
                () -> assertEquals(1, mediaStorage.overwriteCount()),
                () -> assertFalse(contains(stored, GPS_TAGS), "S3 must not keep GPS metadata"),
                () -> assertArrayEquals(PHOTO, stored),
                () -> assertEquals(PHOTO.length, media.getConfirmedSizeBytes()),
                () -> assertEquals(tagged.length, media.getExpectedSizeBytes()),
                () -> assertEquals(VerificationStatus.SUBMITTED, submitted.status())
        );
    }

    @Test
    void submitRejectsAPhotoThatCannotBeSanitized() {
        Fixture fixture = fixture(true);
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "image/jpeg", PHOTO.length);
        mediaStorage.stageContent(new byte[]{1, 2, 3, 4});

        VerificationMediaCommandException exception = assertThrows(
                VerificationMediaCommandException.class,
                () -> mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId())
        );

        assertAll(
                () -> assertEquals(
                        VerificationMediaCommandException.Reason.CONTENT_TYPE_MISMATCH,
                        exception.reason()
                ),
                () -> assertEquals(0, mediaStorage.overwriteCount()),
                () -> assertEquals(VerificationStatus.PENDING_UPLOAD, currentVerification(fixture).getStatus())
        );
    }

    @Test
    void missingOrMismatchedStoredMediaCannotSubmit() {
        Fixture missingFixture = fixture();
        mediaUploadService.issueCurrentUpload(missingFixture.groupId(), missingFixture.userId(), "video/mp4", 100);
        mediaStorage.removeLastObject();

        VerificationMediaCommandException missing = assertThrows(
                VerificationMediaCommandException.class,
                () -> mediaSubmissionService.submitCurrent(missingFixture.groupId(), missingFixture.userId())
        );
        assertEquals(VerificationMediaCommandException.Reason.MEDIA_NOT_UPLOADED, missing.reason());

        Fixture mismatchFixture = fixture();
        mediaUploadService.issueCurrentUpload(mismatchFixture.groupId(), mismatchFixture.userId(), "video/mp4", 100);
        mediaStorage.replaceLastInspection(101, "video/mp4");

        VerificationMediaCommandException mismatch = assertThrows(
                VerificationMediaCommandException.class,
                () -> mediaSubmissionService.submitCurrent(mismatchFixture.groupId(), mismatchFixture.userId())
        );
        assertEquals(VerificationMediaCommandException.Reason.SIZE_MISMATCH, mismatch.reason());
        assertEquals(VerificationStatus.PENDING_UPLOAD, currentVerification(mismatchFixture).getStatus());
    }

    @Test
    void submitWithoutMediaBindingIsRejected() {
        Fixture fixture = fixture();
        service.createOrGetCurrent(fixture.groupId(), fixture.userId());

        VerificationMediaCommandException exception = assertThrows(
                VerificationMediaCommandException.class,
                () -> mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId())
        );

        assertEquals(VerificationMediaCommandException.Reason.MEDIA_NOT_BOUND, exception.reason());
        assertEquals(0, analysisRows(currentVerification(fixture).getId()));
    }

    @Test
    void submittedStateIsIdempotentWithoutSecondHeadOrUploadGrant() {
        Fixture fixture = fixture();
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "video/mp4", 100);
        VerificationSubmissionResult first = mediaSubmissionService.submitCurrent(
                fixture.groupId(), fixture.userId()
        );
        int inspections = mediaStorage.inspectCount();

        VerificationSubmissionResult second = mediaSubmissionService.submitCurrent(
                fixture.groupId(), fixture.userId()
        );

        assertAll(
                () -> assertEquals(first.verificationId(), second.verificationId()),
                () -> assertEquals(first.submittedAt(), second.submittedAt()),
                () -> assertEquals(1, analysisRows(first.verificationId())),
                () -> assertEquals(inspections, mediaStorage.inspectCount()),
                () -> assertThrows(
                        VerificationCommandConflictException.class,
                        () -> mediaUploadService.issueCurrentUpload(
                                fixture.groupId(), fixture.userId(), "video/mp4", 100
                        )
                )
        );
    }

    @Test
    void outerTransactionRollbackRevertsConfirmationAndSubmissionTogether() {
        Fixture fixture = fixture();
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "video/mp4", 100);
        VerificationMediaStorage.StoredMediaInspection inspection = mediaStorage.lastInspection();

        assertThrows(TestRollback.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.submitInspectedCurrent(fixture.groupId(), fixture.userId(), inspection);
            throw new TestRollback();
        }));

        Verification verification = currentVerification(fixture);
        VerificationMedia media = verificationMediaRepository.findByVerification_Id(verification.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationStatus.PENDING_UPLOAD, verification.getStatus()),
                () -> assertNull(verification.getSubmittedAt()),
                () -> assertNull(media.getConfirmedAt()),
                () -> assertNull(media.getConfirmedSizeBytes()),
                () -> assertEquals(0, analysisRows(verification.getId()))
        );
    }

    @Test
    void analysisInsertFailureRollsBackMediaConfirmationAndSubmission() {
        UUID duplicateRequestId = UUID.randomUUID();
        analysisRequestIdGenerator.use(duplicateRequestId);

        Fixture existing = fixture();
        mediaUploadService.issueCurrentUpload(existing.groupId(), existing.userId(), "video/mp4", 100);
        mediaSubmissionService.submitCurrent(existing.groupId(), existing.userId());

        Fixture target = fixture();
        mediaUploadService.issueCurrentUpload(target.groupId(), target.userId(), "video/mp4", 100);
        Long targetVerificationId = currentVerification(target).getId();

        assertThrows(
                DataAccessException.class,
                () -> mediaSubmissionService.submitCurrent(target.groupId(), target.userId())
        );

        Verification verification = currentVerification(target);
        VerificationMedia media = verificationMediaRepository.findByVerification_Id(targetVerificationId).orElseThrow();
        assertAll(
                () -> assertEquals(VerificationStatus.PENDING_UPLOAD, verification.getStatus()),
                () -> assertNull(verification.getSubmittedAt()),
                () -> assertNull(media.getConfirmedAt()),
                () -> assertNull(media.getConfirmedSizeBytes()),
                () -> assertEquals(0, analysisRows(targetVerificationId)),
                () -> assertEquals(1, jdbcTemplate.queryForObject(
                        "select count(*) from verification_analysis where analysis_request_id = ?",
                        Integer.class,
                        duplicateRequestId.toString()
                ))
        );
    }

    @Test
    void v5UniqueAndCheckConstraintsAreEnforced() {
        Fixture fixture = fixture();
        Verification verification = service.createOrGetCurrent(fixture.groupId(), fixture.userId());
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "video/mp4", 100);
        Fixture invalidSizeFixture = fixture();
        Verification invalidSizeVerification = service.createOrGetCurrent(
                invalidSizeFixture.groupId(), invalidSizeFixture.userId()
        );
        Fixture duplicateKeyFixture = fixture();
        Verification duplicateKeyVerification = service.createOrGetCurrent(
                duplicateKeyFixture.groupId(), duplicateKeyFixture.userId()
        );
        String existingObjectKey = jdbcTemplate.queryForObject(
                "select object_key from verification_media where verification_id = ?",
                String.class,
                verification.getId()
        );
        String columns = "(verification_id, object_key, content_type, expected_size_bytes, confirmed_size_bytes,"
                + " confirmed_at, created_at, updated_at)";

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "insert into verification_media " + columns + " values (?, ?, 'video/mp4', 100, null, null, current_timestamp, current_timestamp)",
                verification.getId(),
                "verification-media/duplicate-verification"
        ));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "insert into verification_media " + columns + " values (?, ?, 'video/mp4', 0, null, null, current_timestamp, current_timestamp)",
                invalidSizeVerification.getId(),
                "verification-media/invalid-size"
        ));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "insert into verification_media " + columns + " values (?, ?, 'video/mp4', 100, null, null, current_timestamp, current_timestamp)",
                duplicateKeyVerification.getId(),
                existingObjectKey
        ));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "update verification_media set confirmed_size_bytes = 100 where verification_id = ?",
                verification.getId()
        ));
    }

    private Verification currentVerification(Fixture fixture) {
        return verificationRepository
                .findByGroupMember_IdAndRoutineSchedule_IdAndScheduledDate(
                        fixture.memberId(),
                        fixture.scheduleId(),
                        LocalDate.of(2026, 8, 11)
                )
                .orElseThrow();
    }

    private Fixture fixture() {
        return fixture(false);
    }

    private Fixture fixture(boolean verificationBound) {
        return inTransaction(() -> {
            User user = User.create();
            RoutineDefinition definition = new RoutineDefinition("water", null);
            entityManager.persist(user);
            entityManager.persist(definition);
            RoutineGroup group = verificationBound
                    ? new RoutineGroup(
                            definition,
                            user,
                            "water group",
                            GroupVisibility.PUBLIC,
                            RoutineGroupStatus.ACTIVE,
                            5,
                            1,
                            verificationTemplateCatalog.requireTemplate(
                                    VerificationTemplateCatalog.MEAL_PHOTO_RECORD
                            )
                    )
                    : new RoutineGroup(
                            definition,
                            user,
                            "water group",
                            GroupVisibility.PUBLIC,
                            RoutineGroupStatus.ACTIVE,
                            5,
                            1
                    );
            entityManager.persist(group);
            RoutineSchedule schedule = new RoutineSchedule(
                    group,
                    ScheduleType.DAILY,
                    LocalDate.of(2026, 8, 10),
                    LocalDate.of(2026, 8, 16),
                    LocalTime.of(23, 0),
                    "Asia/Seoul",
                    Set.of()
            );
            entityManager.persist(schedule);
            GroupMember member = new GroupMember(
                    group,
                    user,
                    GroupMemberRole.OWNER,
                    GroupMemberStatus.JOINED,
                    Instant.parse("2026-08-01T00:00:00Z")
            );
            member.startParticipation(Instant.parse("2026-08-01T00:00:00Z"));
            entityManager.persist(member);
            entityManager.flush();
            return new Fixture(group.getId(), user.getId(), member.getId(), schedule.getId());
        });
    }

    private int currentRows(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from verification
                        where group_member_id = ?
                          and routine_schedule_id = ?
                          and scheduled_date = ?
                        """,
                Integer.class,
                fixture.memberId(),
                fixture.scheduleId(),
                LocalDate.of(2026, 8, 11)
        );
    }

    private int analysisRows(Long verificationId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from verification_analysis where verification_id = ?",
                Integer.class,
                verificationId
        );
    }

    private void assertPendingUnconfirmed(Fixture fixture) {
        Verification verification = currentVerification(fixture);
        VerificationMedia media = verificationMediaRepository
                .findByVerification_Id(verification.getId())
                .orElseThrow();
        assertAll(
                () -> assertEquals(VerificationStatus.PENDING_UPLOAD, verification.getStatus()),
                () -> assertNull(verification.getSubmittedAt()),
                () -> assertNull(media.getConfirmedAt()),
                () -> assertNull(media.getConfirmedSizeBytes()),
                () -> assertEquals(0, analysisRows(verification.getId()))
        );
    }

    private String uploadOutcome(Fixture fixture, String contentType) {
        try {
            mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), contentType, 100);
            return "SUCCESS";
        } catch (VerificationMediaCommandException exception) {
            return exception.reason().name();
        }
    }

    private <T> List<T> concurrently(Supplier<T> command) throws Exception {
        return concurrently(command, command);
    }

    private <T> List<T> concurrently(Supplier<T> firstCommand, Supplier<T> secondCommand) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> first = executor.submit(() -> runWhenReleased(firstCommand, ready, start));
            Future<T> second = executor.submit(() -> runWhenReleased(secondCommand, ready, start));
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent commands did not become ready");
            }
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> T runWhenReleased(
            Supplier<T> command,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent command was not released");
        }
        return command.get();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private record Fixture(Long groupId, Long userId, Long memberId, Long scheduleId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        MutableClock verificationCommandTestClock() {
            return new MutableClock(SNAPSHOT_NOW);
        }

        @Bean
        @Primary
        TestMediaStorage verificationTestMediaStorage() {
            return new TestMediaStorage();
        }

        @Bean
        @Primary
        MutableAnalysisRequestIdGenerator verificationTestAnalysisRequestIdGenerator() {
            return new MutableAnalysisRequestIdGenerator();
        }
    }

    /** ISO-8859-1 maps every byte 1:1, so this is an exact byte-subsequence search. */
    private static boolean contains(byte[] haystack, byte[] needle) {
        return new String(haystack, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(new String(needle, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    static final class TestMediaStorage implements VerificationMediaStorage {

        private final Map<String, StoredMediaInspection> media = new ConcurrentHashMap<>();
        private final Map<String, byte[]> contents = new ConcurrentHashMap<>();
        private final List<String> issuedKeys = new CopyOnWriteArrayList<>();
        private final AtomicInteger inspectCount = new AtomicInteger();
        private final AtomicInteger overwriteCount = new AtomicInteger();
        private final AtomicReference<Runnable> beforeInspect = new AtomicReference<>(() -> {
        });
        private volatile boolean issueTransactionActive;
        private volatile boolean inspectTransactionActive;

        @Override
        public UploadGrant issueUpload(
                String objectKey,
                String contentType,
                long sizeBytes,
                Instant expiresAt
        ) {
            issueTransactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            issuedKeys.add(objectKey);
            media.put(objectKey, new StoredMediaInspection(objectKey, sizeBytes, contentType));
            contents.put(
                    objectKey,
                    "image/jpeg".equals(contentType) && sizeBytes == PHOTO.length
                            ? PHOTO.clone()
                            : new byte[Math.toIntExact(sizeBytes)]
            );
            return new UploadGrant(
                    URI.create("https://example.invalid/upload"),
                    "PUT",
                    Map.of("content-type", List.of(contentType), "if-none-match", List.of("*")),
                    expiresAt
            );
        }

        @Override
        public StoredMediaInspection inspect(String objectKey) {
            inspectTransactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            inspectCount.incrementAndGet();
            beforeInspect.get().run();
            StoredMediaInspection inspection = media.get(objectKey);
            if (inspection == null) {
                throw new StorageException(StorageException.Reason.NOT_FOUND, "missing test object");
            }
            return inspection;
        }

        @Override
        public StoredMedia acquire(String objectKey, long maxBytes) {
            byte[] content = contents.get(objectKey);
            StoredMediaInspection inspection = media.get(objectKey);
            if (content == null || inspection == null) {
                throw new StorageException(StorageException.Reason.NOT_FOUND, "missing test object");
            }
            return new StoredMedia(objectKey, content.length, inspection.contentType(), content);
        }

        @Override
        public URI issueDownload(String objectKey, Instant expiresAt) {
            if (!contents.containsKey(objectKey)) {
                throw new StorageException(StorageException.Reason.NOT_FOUND, "missing test object");
            }
            return URI.create("https://download.example.invalid/" + objectKey);
        }

        @Override
        public void overwrite(String objectKey, String contentType, byte[] content) {
            overwriteCount.incrementAndGet();
            contents.put(objectKey, content.clone());
            media.put(objectKey, new StoredMediaInspection(objectKey, content.length, contentType));
        }

        /** Replaces what the last issued key actually holds, so a test can upload real photo bytes. */
        void stageContent(byte[] content) {
            String key = issuedKeys.getLast();
            contents.put(key, content.clone());
            media.put(key, new StoredMediaInspection(key, content.length, media.get(key).contentType()));
        }

        byte[] storedContent() {
            return contents.get(issuedKeys.getLast()).clone();
        }

        int overwriteCount() {
            return overwriteCount.get();
        }

        void reset() {
            media.clear();
            contents.clear();
            overwriteCount.set(0);
            issuedKeys.clear();
            inspectCount.set(0);
            beforeInspect.set(() -> {
            });
            issueTransactionActive = false;
            inspectTransactionActive = false;
        }

        List<String> issuedKeys() {
            return List.copyOf(issuedKeys);
        }

        boolean issueTransactionActive() {
            return issueTransactionActive;
        }

        boolean inspectTransactionActive() {
            return inspectTransactionActive;
        }

        int inspectCount() {
            return inspectCount.get();
        }

        void beforeInspect(Runnable work) {
            beforeInspect.set(work);
        }

        void removeLastObject() {
            media.remove(issuedKeys.getLast());
        }

        void replaceLastInspection(long sizeBytes, String contentType) {
            String key = issuedKeys.getLast();
            media.put(key, new StoredMediaInspection(key, sizeBytes, contentType));
            contents.put(key, new byte[Math.toIntExact(sizeBytes)]);
        }

        StoredMediaInspection lastInspection() {
            return media.get(issuedKeys.getLast());
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;

        MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    static final class MutableAnalysisRequestIdGenerator extends AnalysisRequestIdGenerator {

        private final AtomicReference<UUID> fixed = new AtomicReference<>();

        @Override
        public UUID generate() {
            UUID value = fixed.get();
            return value == null ? super.generate() : value;
        }

        void use(UUID value) {
            fixed.set(value);
        }

        void reset() {
            fixed.set(null);
        }
    }

    private static final class TestRollback extends RuntimeException {
    }
}

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
import com.allog.verification.domain.Verification;
import com.allog.verification.domain.VerificationMedia;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.repository.VerificationMediaRepository;
import com.allog.verification.repository.VerificationRepository;
import com.allog.verification.storage.VerificationMediaStorage;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestMediaStorage mediaStorage;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetMediaBoundary() {
        clock.set(SNAPSHOT_NOW);
        mediaStorage.reset();
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
        ).getSubmittedAt());
        Verification verification = currentVerification(fixture);
        VerificationMedia media = verificationMediaRepository
                .findByVerification_Id(verification.getId())
                .orElseThrow();

        assertAll(
                () -> assertEquals(SNAPSHOT_NOW, submittedAt.get(0)),
                () -> assertEquals(submittedAt.get(0), submittedAt.get(1)),
                () -> assertEquals(VerificationStatus.SUBMITTED, verification.getStatus()),
                () -> assertEquals(verification.getSubmittedAt(), media.getConfirmedAt()),
                () -> assertEquals(SNAPSHOT_NOW, inTransaction(() -> verificationRepository
                        .findByGroupMember_IdAndRoutineSchedule_IdAndScheduledDate(
                                fixture.memberId(), fixture.scheduleId(), LocalDate.of(2026, 8, 11)
                        )
                        .orElseThrow()
                        .getSubmittedAt()))
        );
    }

    @Test
    void flywayHasExactlyV1ThroughV5() {
        assertAll(
                () -> assertEquals(5, jdbcTemplate.queryForObject(
                        "select count(*) from flyway_schema_history where success = true and version is not null",
                        Integer.class
                )),
                () -> assertEquals(1, jdbcTemplate.queryForObject(
                        "select count(*) from flyway_schema_history where version = '5'",
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

        Verification submitted = mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId());
        VerificationMedia media = verificationMediaRepository.findByVerification_Id(submitted.getId()).orElseThrow();

        assertAll(
                () -> assertEquals(VerificationStatus.SUBMITTED, submitted.getStatus()),
                () -> assertEquals(SNAPSHOT_NOW, submitted.getSubmittedAt()),
                () -> assertEquals(SNAPSHOT_NOW, media.getConfirmedAt()),
                () -> assertEquals(100L, media.getConfirmedSizeBytes()),
                () -> assertFalse(mediaStorage.issueTransactionActive()),
                () -> assertFalse(mediaStorage.inspectTransactionActive())
        );
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
                () -> assertFalse(mediaStorage.inspectTransactionActive())
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
        mediaStorage.replaceLastInspection(99, "image/jpeg");

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
    }

    @Test
    void submittedStateIsIdempotentWithoutSecondHeadOrUploadGrant() {
        Fixture fixture = fixture();
        mediaUploadService.issueCurrentUpload(fixture.groupId(), fixture.userId(), "video/mp4", 100);
        Verification first = mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId());
        int inspections = mediaStorage.inspectCount();

        Verification second = mediaSubmissionService.submitCurrent(fixture.groupId(), fixture.userId());

        assertAll(
                () -> assertEquals(first.getId(), second.getId()),
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
                () -> assertNull(media.getConfirmedSizeBytes())
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
        return inTransaction(() -> {
            User user = User.create();
            RoutineDefinition definition = new RoutineDefinition("water", null);
            entityManager.persist(user);
            entityManager.persist(definition);
            RoutineGroup group = new RoutineGroup(
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
    }

    static final class TestMediaStorage implements VerificationMediaStorage {

        private final Map<String, StoredMediaInspection> media = new ConcurrentHashMap<>();
        private final List<String> issuedKeys = new CopyOnWriteArrayList<>();
        private final AtomicInteger inspectCount = new AtomicInteger();
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

        void reset() {
            media.clear();
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

    private static final class TestRollback extends RuntimeException {
    }
}

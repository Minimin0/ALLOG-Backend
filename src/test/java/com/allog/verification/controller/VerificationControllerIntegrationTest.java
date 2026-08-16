package com.allog.verification.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=${VERIFICATION_HTTP_TEST_DB_URL:jdbc:h2:mem:verification-http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${VERIFICATION_HTTP_TEST_DB_USERNAME:sa}",
        "spring.datasource.password=${VERIFICATION_HTTP_TEST_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=${VERIFICATION_HTTP_TEST_DB_DRIVER:org.h2.Driver}",
        "allog.auth.firebase.enabled=false",
        "allog.verification.media.enabled=true",
        "allog.verification.media.bucket=test-bucket",
        "allog.verification.media.region=ap-northeast-2",
        "allog.verification.media.max-bytes=1000",
        "allog.verification.media.upload-expiry=5m",
        "allog.verification.media.allowed-content-types=video/mp4,image/jpeg"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(VerificationControllerIntegrationTest.TestConfig.class)
class VerificationControllerIntegrationTest {

    private static final Instant SNAPSHOT_NOW = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-08-11T14:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private VerificationRepository verificationRepository;

    @Autowired
    private VerificationMediaRepository mediaRepository;

    @Autowired
    private TestMediaStorage storage;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetBoundary() {
        clock.set(SNAPSHOT_NOW);
        storage.reset();
    }

    @Test
    void authenticatedHttpFlowCreatesUploadsAndSubmitsWithoutEntityOrTransactionLeakage() throws Exception {
        Fixture fixture = fixture(GroupVisibility.PRIVATE, GroupMemberStatus.ACTIVE);
        String current = currentEndpoint(fixture.groupId());

        mockMvc.perform(authenticatedPost(current, fixture.userId())
                        .queryParam("userId", "999")
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.scheduledDate").value("2026-08-11"))
                .andExpect(jsonPath("$.status").value("PENDING_UPLOAD"))
                .andExpect(jsonPath("$.submissionDeadline").value("2026-08-11T14:00:00Z"))
                .andExpect(jsonPath("$.groupMember").doesNotExist())
                .andExpect(jsonPath("$.routineSchedule").doesNotExist());

        Verification verification = currentVerification(fixture);
        mockMvc.perform(authenticatedPost(current, fixture.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationId").value(verification.getId()))
                .andExpect(jsonPath("$.status").value("PENDING_UPLOAD"));

        mockMvc.perform(validUpload(fixture, "video/mp4", 123))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.method").value("PUT"))
                .andExpect(jsonPath("$.requiredHeaders.content-type[0]").value("video/mp4"))
                .andExpect(jsonPath("$.requiredHeaders.content-length[0]").value("123"))
                .andExpect(jsonPath("$.requiredHeaders.if-none-match[0]").value("*"))
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.bucket").doesNotExist());

        mockMvc.perform(authenticatedPost(current + "/submit", fixture.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.verificationId").value(verification.getId()))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submittedAt").value("2026-08-11T10:00:00Z"));

        int inspections = storage.inspectCount();
        mockMvc.perform(authenticatedPost(current + "/submit", fixture.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submittedAt").value("2026-08-11T10:00:00Z"));
        mockMvc.perform(authenticatedPost(current, fixture.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        Verification submitted = currentVerification(fixture);
        VerificationMedia media = mediaRepository.findByVerification_Id(verification.getId()).orElseThrow();
        assertEquals(inspections, storage.inspectCount());
        assertEquals(submitted.getSubmittedAt(), media.getConfirmedAt());
        assertFalse(storage.issueTransactionActive());
        assertFalse(storage.inspectTransactionActive());
    }

    @Test
    void mediaProofFailuresAndDeadlineCrossingRemainStatusOnlyConflicts() throws Exception {
        Fixture noBinding = fixture(GroupVisibility.PUBLIC, GroupMemberStatus.ACTIVE);
        mockMvc.perform(authenticatedPost(currentEndpoint(noBinding.groupId()), noBinding.userId()))
                .andExpect(status().isOk());
        mockMvc.perform(authenticatedPost(currentEndpoint(noBinding.groupId()) + "/submit", noBinding.userId()))
                .andExpect(status().isConflict())
                .andExpect(content().string(""));

        Fixture missingObject = fixture(GroupVisibility.PUBLIC, GroupMemberStatus.ACTIVE);
        mockMvc.perform(validUpload(missingObject, "video/mp4", 123)).andExpect(status().isOk());
        storage.removeLastObject();
        mockMvc.perform(authenticatedPost(
                        currentEndpoint(missingObject.groupId()) + "/submit",
                        missingObject.userId()
                ))
                .andExpect(status().isConflict())
                .andExpect(content().string(""));

        Fixture mismatch = fixture(GroupVisibility.PUBLIC, GroupMemberStatus.ACTIVE);
        mockMvc.perform(validUpload(mismatch, "video/mp4", 123)).andExpect(status().isOk());
        storage.replaceLastInspection(123, "image/jpeg");
        mockMvc.perform(authenticatedPost(currentEndpoint(mismatch.groupId()) + "/submit", mismatch.userId()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().string(""));

        Fixture crossing = fixture(GroupVisibility.PUBLIC, GroupMemberStatus.ACTIVE);
        mockMvc.perform(validUpload(crossing, "video/mp4", 123)).andExpect(status().isOk());
        storage.beforeInspect(() -> clock.set(DEADLINE));
        mockMvc.perform(authenticatedPost(currentEndpoint(crossing.groupId()) + "/submit", crossing.userId()))
                .andExpect(status().isConflict())
                .andExpect(content().string(""));

        Verification pending = currentVerification(crossing);
        VerificationMedia unconfirmed = mediaRepository.findByVerification_Id(pending.getId()).orElseThrow();
        assertEquals(VerificationStatus.PENDING_UPLOAD, pending.getStatus());
        assertNull(pending.getSubmittedAt());
        assertNull(unconfirmed.getConfirmedAt());
        assertFalse(storage.inspectTransactionActive());
    }

    @Test
    void lifecycleAndCrossUserVisibilityCannotBypassMembershipAuthorization() throws Exception {
        for (GroupMemberStatus statusValue : List.of(
                GroupMemberStatus.JOINED,
                GroupMemberStatus.COMPLETED,
                GroupMemberStatus.FAILED
        )) {
            Fixture fixture = fixture(GroupVisibility.PUBLIC, statusValue);
            expectAllCommands(fixture, fixture.userId(), 409);
        }
        for (GroupMemberStatus statusValue : List.of(GroupMemberStatus.LEFT, GroupMemberStatus.REMOVED)) {
            Fixture fixture = fixture(GroupVisibility.PUBLIC, statusValue);
            expectAllCommands(fixture, fixture.userId(), 404);
        }

        Fixture publicGroup = fixture(GroupVisibility.PUBLIC, GroupMemberStatus.ACTIVE);
        Fixture privateGroup = fixture(GroupVisibility.PRIVATE, GroupMemberStatus.ACTIVE);
        User outsider = inTransaction(() -> {
            User user = User.create();
            entityManager.persist(user);
            return user;
        });

        expectAllCommands(publicGroup, outsider.getId(), 404);
        expectAllCommands(privateGroup, outsider.getId(), 404);
    }

    @Test
    void actualPolicyAndStorageFailuresMapAtHttpBoundary() throws Exception {
        Fixture fixture = fixture(GroupVisibility.PUBLIC, GroupMemberStatus.ACTIVE);

        mockMvc.perform(validUpload(fixture, "video/quicktime", 123))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(validUpload(fixture, "video/*", 123))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(validUpload(fixture, "video/mp4; charset=UTF-8", 123))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(validUpload(fixture, "video/mp4", 1001))
                .andExpect(status().isContentTooLarge());

        storage.issueFailure(VerificationMediaStorage.StorageException.Reason.UNAVAILABLE);
        mockMvc.perform(validUpload(fixture, "video/mp4", 123))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(""));
        storage.issueFailure(VerificationMediaStorage.StorageException.Reason.CONFIGURATION);
        mockMvc.perform(validUpload(fixture, "video/mp4", 123))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
    }

    @Test
    void existingCurrentRemainsReadableAfterDeadlineButNewGrantIsClosed() throws Exception {
        Fixture fixture = fixture(GroupVisibility.PUBLIC, GroupMemberStatus.ACTIVE);
        String current = currentEndpoint(fixture.groupId());
        mockMvc.perform(authenticatedPost(current, fixture.userId())).andExpect(status().isOk());
        clock.set(DEADLINE);

        mockMvc.perform(authenticatedPost(current, fixture.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_UPLOAD"));
        mockMvc.perform(validUpload(fixture, "video/mp4", 123))
                .andExpect(status().isConflict())
                .andExpect(content().string(""));
        assertTrue(storage.issuedKeys().isEmpty());
    }

    @Test
    void duplicateSubmitPreservesApprovedStatusAndRetryReopensUpload() throws Exception {
        Fixture approvedFixture = fixture(GroupVisibility.PUBLIC, GroupMemberStatus.ACTIVE);
        mockMvc.perform(validUpload(approvedFixture, "video/mp4", 123)).andExpect(status().isOk());
        mockMvc.perform(authenticatedPost(
                        currentEndpoint(approvedFixture.groupId()) + "/submit",
                        approvedFixture.userId()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submittedAt").value("2026-08-11T10:00:00Z"));
        inTransaction(() -> {
            Verification verification = currentVerification(approvedFixture);
            verification.startProcessing();
            verification.approve(Clock.fixed(SNAPSHOT_NOW.plusSeconds(60), ZoneOffset.UTC));
            return null;
        });
        mockMvc.perform(authenticatedPost(
                        currentEndpoint(approvedFixture.groupId()) + "/submit",
                        approvedFixture.userId()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.submittedAt").value("2026-08-11T10:00:00Z"));

        Fixture retryFixture = fixture(GroupVisibility.PUBLIC, GroupMemberStatus.ACTIVE);
        mockMvc.perform(validUpload(retryFixture, "video/mp4", 123)).andExpect(status().isOk());
        mockMvc.perform(authenticatedPost(
                currentEndpoint(retryFixture.groupId()) + "/submit",
                retryFixture.userId()
        )).andExpect(status().isOk());
        inTransaction(() -> {
            Verification verification = currentVerification(retryFixture);
            verification.startProcessing();
            verification.requestRetry();
            return null;
        });

        // the guided retry reopens the upload that used to be refused with a conflict
        mockMvc.perform(validUpload(retryFixture, "video/mp4", 123)).andExpect(status().isOk());
        assertEquals(
                VerificationStatus.RETRY_REQUIRED,
                currentVerification(retryFixture).getStatus()
        );
    }

    private void expectAllCommands(Fixture fixture, Long requesterId, int expectedStatus) throws Exception {
        String current = currentEndpoint(fixture.groupId());
        mockMvc.perform(authenticatedPost(current, requesterId))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().string(""));
        mockMvc.perform(authenticatedPost(current + "/upload-intent", requesterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"video/mp4\",\"sizeBytes\":123}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().string(""));
        mockMvc.perform(authenticatedPost(current + "/submit", requesterId))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().string(""));
    }

    private MockHttpServletRequestBuilder validUpload(Fixture fixture, String contentType, long sizeBytes) {
        return authenticatedPost(currentEndpoint(fixture.groupId()) + "/upload-intent", fixture.userId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contentType":"%s","sizeBytes":%d}
                        """.formatted(contentType, sizeBytes));
    }

    private MockHttpServletRequestBuilder authenticatedPost(String endpoint, Long userId) {
        return post(endpoint).with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(userId)
        )));
    }

    private String currentEndpoint(Long groupId) {
        return "/api/v1/me/groups/" + groupId + "/verifications/current";
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

    private Fixture fixture(GroupVisibility visibility, GroupMemberStatus memberStatus) {
        return inTransaction(() -> {
            User user = User.create();
            RoutineDefinition definition = new RoutineDefinition("water", null);
            entityManager.persist(user);
            entityManager.persist(definition);
            RoutineGroup group = new RoutineGroup(
                    definition,
                    user,
                    "water group",
                    visibility,
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
                    memberStatus == GroupMemberStatus.ACTIVE ? GroupMemberStatus.JOINED : memberStatus,
                    Instant.parse("2026-08-01T00:00:00Z")
            );
            if (memberStatus == GroupMemberStatus.ACTIVE) {
                member.startParticipation(Instant.parse("2026-08-01T00:00:00Z"));
            }
            entityManager.persist(member);
            entityManager.flush();
            return new Fixture(group.getId(), user.getId(), member.getId(), schedule.getId());
        });
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
        MutableClock verificationHttpClock() {
            return new MutableClock(SNAPSHOT_NOW);
        }

        @Bean
        @Primary
        TestMediaStorage verificationHttpStorage() {
            return new TestMediaStorage();
        }
    }

    static final class TestMediaStorage implements VerificationMediaStorage {

        private final Map<String, StoredMediaInspection> media = new ConcurrentHashMap<>();
        private final AtomicInteger inspectCount = new AtomicInteger();
        private final AtomicReference<Runnable> beforeInspect = new AtomicReference<>(() -> {
        });
        private final AtomicReference<StorageException.Reason> issueFailure = new AtomicReference<>();
        private final AtomicReference<String> lastKey = new AtomicReference<>();
        private volatile boolean issueTransactionActive;
        private volatile boolean inspectTransactionActive;

        @Override
        public UploadGrant issueUpload(String objectKey, String contentType, long sizeBytes, Instant expiresAt) {
            issueTransactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            StorageException.Reason failure = issueFailure.getAndSet(null);
            if (failure != null) {
                throw new StorageException(failure, "test storage failure");
            }
            lastKey.set(objectKey);
            media.put(objectKey, new StoredMediaInspection(objectKey, sizeBytes, contentType));
            return new UploadGrant(
                    URI.create("https://upload.example.invalid/temporary"),
                    "PUT",
                    Map.of(
                            "content-type", List.of(contentType),
                            "content-length", List.of(Long.toString(sizeBytes)),
                            "if-none-match", List.of("*")
                    ),
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
                throw new StorageException(StorageException.Reason.NOT_FOUND, "test object missing");
            }
            return inspection;
        }

        void reset() {
            media.clear();
            inspectCount.set(0);
            beforeInspect.set(() -> {
            });
            issueFailure.set(null);
            lastKey.set(null);
            issueTransactionActive = false;
            inspectTransactionActive = false;
        }

        void removeLastObject() {
            media.remove(lastKey.get());
        }

        void replaceLastInspection(long sizeBytes, String contentType) {
            String key = lastKey.get();
            media.put(key, new StoredMediaInspection(key, sizeBytes, contentType));
        }

        void beforeInspect(Runnable action) {
            beforeInspect.set(action);
        }

        void issueFailure(StorageException.Reason reason) {
            issueFailure.set(reason);
        }

        List<String> issuedKeys() {
            return lastKey.get() == null ? List.of() : List.of(lastKey.get());
        }

        int inspectCount() {
            return inspectCount.get();
        }

        boolean issueTransactionActive() {
            return issueTransactionActive;
        }

        boolean inspectTransactionActive() {
            return inspectTransactionActive;
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
}

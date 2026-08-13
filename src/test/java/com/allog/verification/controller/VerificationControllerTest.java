package com.allog.verification.controller;

import com.allog.auth.security.AllogPrincipal;
import com.allog.auth.security.FirebaseBearerAuthenticationToken;
import com.allog.verification.domain.VerificationStatus;
import com.allog.verification.service.VerificationCommandConflictException;
import com.allog.verification.service.VerificationCommandService;
import com.allog.verification.service.VerificationCurrentResult;
import com.allog.verification.service.VerificationMediaCommandException;
import com.allog.verification.service.VerificationMediaSubmissionService;
import com.allog.verification.service.VerificationMediaUploadService;
import com.allog.verification.service.VerificationMembershipNotFoundException;
import com.allog.verification.service.VerificationSubmissionResult;
import com.allog.verification.storage.VerificationMediaStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "allog.auth.firebase.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VerificationControllerTest {

    private static final Long GROUP_ID = 42L;
    private static final Long USER_ID = 17L;
    private static final String CURRENT = "/api/v1/me/groups/42/verifications/current";
    private static final String UPLOAD = CURRENT + "/upload-intent";
    private static final String SUBMIT = CURRENT + "/submit";
    private static final Instant DEADLINE = Instant.parse("2026-08-13T13:00:00Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-08-13T12:54:30Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerificationCommandService commandService;

    @MockitoBean
    private VerificationMediaUploadService uploadService;

    @MockitoBean
    private VerificationMediaSubmissionService submissionService;

    @Test
    void currentReturnsExactContractUsingOnlyAuthenticatedPrincipal() throws Exception {
        when(commandService.createOrGetCurrentResult(GROUP_ID, USER_ID)).thenReturn(
                new VerificationCurrentResult(
                        123L,
                        LocalDate.of(2026, 8, 13),
                        VerificationStatus.PENDING_UPLOAD,
                        DEADLINE
                )
        );

        mockMvc.perform(authenticatedPost(CURRENT)
                        .queryParam("userId", "999")
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.verificationId").value(123))
                .andExpect(jsonPath("$.scheduledDate").value("2026-08-13"))
                .andExpect(jsonPath("$.status").value("PENDING_UPLOAD"))
                .andExpect(jsonPath("$.submissionDeadline").value("2026-08-13T13:00:00Z"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.memberId").doesNotExist())
                .andExpect(jsonPath("$.routineScheduleId").doesNotExist())
                .andExpect(jsonPath("$.objectKey").doesNotExist());

        verify(commandService).createOrGetCurrentResult(GROUP_ID, USER_ID);
        verify(commandService, never()).createOrGetCurrentResult(GROUP_ID, 999L);
    }

    @Test
    void uploadIntentPreservesRequiredHeadersAndReturnsNoStoreWithoutStorageInternals() throws Exception {
        when(uploadService.issueCurrentUpload(GROUP_ID, USER_ID, "video/mp4", 123L)).thenReturn(
                new VerificationMediaStorage.UploadGrant(
                        URI.create("https://upload.example.invalid/temporary"),
                        "PUT",
                        Map.of(
                                "content-type", List.of("video/mp4"),
                                "content-length", List.of("123"),
                                "if-none-match", List.of("*"),
                                "x-test-multi-value", List.of("first", "second")
                        ),
                        DEADLINE
                )
        );

        mockMvc.perform(authenticatedPost(UPLOAD)
                        .queryParam("userId", "999")
                        .header("X-User-Id", "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "video/mp4",
                                  "sizeBytes": 123,
                                  "userId": 999,
                                  "objectKey": "client-key"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", CacheControl.noStore().getHeaderValue()))
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.method").value("PUT"))
                .andExpect(jsonPath("$.uploadUrl").value("https://upload.example.invalid/temporary"))
                .andExpect(jsonPath("$.requiredHeaders.*", hasSize(4)))
                .andExpect(jsonPath("$.requiredHeaders.content-type[0]").value("video/mp4"))
                .andExpect(jsonPath("$.requiredHeaders.content-length[0]").value("123"))
                .andExpect(jsonPath("$.requiredHeaders.if-none-match[0]").value("*"))
                .andExpect(jsonPath("$.requiredHeaders.x-test-multi-value", hasSize(2)))
                .andExpect(jsonPath("$.requiredHeaders.x-test-multi-value[0]").value("first"))
                .andExpect(jsonPath("$.requiredHeaders.x-test-multi-value[1]").value("second"))
                .andExpect(jsonPath("$.expiresAt").value("2026-08-13T13:00:00Z"))
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.bucket").doesNotExist())
                .andExpect(jsonPath("$.region").doesNotExist())
                .andExpect(jsonPath("$.mediaId").doesNotExist());

        verify(uploadService).issueCurrentUpload(GROUP_ID, USER_ID, "video/mp4", 123L);
        verify(uploadService, never()).issueCurrentUpload(GROUP_ID, 999L, "video/mp4", 123L);
    }

    @Test
    void submitReturnsExactPersistedStatusAndTimestamp() throws Exception {
        when(submissionService.submitCurrent(GROUP_ID, USER_ID)).thenReturn(new VerificationSubmissionResult(
                123L,
                LocalDate.of(2026, 8, 13),
                VerificationStatus.APPROVED,
                SUBMITTED_AT
        ));

        mockMvc.perform(authenticatedPost(SUBMIT)
                        .queryParam("userId", "999")
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.verificationId").value(123))
                .andExpect(jsonPath("$.scheduledDate").value("2026-08-13"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.submittedAt").value("2026-08-13T12:54:30Z"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.objectKey").doesNotExist());

        verify(submissionService).submitCurrent(GROUP_ID, USER_ID);
        verify(submissionService, never()).submitCurrent(GROUP_ID, 999L);
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void unauthenticatedEndpointsReturn401WithoutCallingApplicationServices(String endpoint) throws Exception {
        MockHttpServletRequestBuilder request = post(endpoint);
        if (endpoint.endsWith("upload-intent")) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"contentType\":\"video/mp4\",\"sizeBytes\":123}");
        }

        mockMvc.perform(request).andExpect(status().isUnauthorized());

        verifyNoInteractions(commandService, uploadService, submissionService);
    }

    @ParameterizedTest
    @MethodSource("endpointPatterns")
    void nonPositiveGroupIdReturns400WithoutCallingServices(String endpoint, long groupId) throws Exception {
        MockHttpServletRequestBuilder request = authenticatedPost(endpoint.formatted(groupId));
        if (endpoint.endsWith("upload-intent")) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content("{\"contentType\":\"video/mp4\",\"sizeBytes\":123}");
        }

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        verifyNoInteractions(commandService, uploadService, submissionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"contentType\":\"\",\"sizeBytes\":123}",
            "{\"contentType\":\"video/mp4\",\"sizeBytes\":0}",
            "{\"contentType\":\"video/mp4\",\"sizeBytes\":-1}",
            "{malformed"
    })
    void invalidUploadRequestReturnsStatusOnly400(String json) throws Exception {
        mockMvc.perform(authenticatedPost(UPLOAD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        verifyNoInteractions(uploadService);
    }

    @ParameterizedTest
    @MethodSource("mediaErrorMatrix")
    void mediaErrorsMapToStatusOnlyResponses(
            VerificationMediaCommandException.Reason reason,
            int expectedStatus
    ) throws Exception {
        when(uploadService.issueCurrentUpload(anyLong(), anyLong(), anyString(), anyLong()))
                .thenThrow(new VerificationMediaCommandException(reason, "secret objectKey and userId=17"));

        mockMvc.perform(validUpload())
                .andExpect(status().is(expectedStatus))
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @MethodSource("storageErrorMatrix")
    void storageErrorsMapWithoutLeakingDetails(
            VerificationMediaStorage.StorageException.Reason reason,
            int expectedStatus
    ) throws Exception {
        when(uploadService.issueCurrentUpload(anyLong(), anyLong(), anyString(), anyLong()))
                .thenThrow(new VerificationMediaStorage.StorageException(
                        reason,
                        "bucket=private-bucket objectKey=verification-media/secret"
                ));

        mockMvc.perform(validUpload())
                .andExpect(status().is(expectedStatus))
                .andExpect(content().string(""))
                .andExpect(content().string(not(containsString("private-bucket"))))
                .andExpect(content().string(not(containsString("objectKey"))));
    }

    @Test
    void hiddenMembershipConflictAndInvariantUseStatusOnlyErrors() throws Exception {
        when(commandService.createOrGetCurrentResult(GROUP_ID, USER_ID))
                .thenThrow(new VerificationMembershipNotFoundException())
                .thenThrow(new VerificationCommandConflictException("groupId=42 userId=17 deadline"))
                .thenThrow(new IllegalStateException("bucket=secret objectKey=secret"));

        mockMvc.perform(authenticatedPost(CURRENT))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        mockMvc.perform(authenticatedPost(CURRENT))
                .andExpect(status().isConflict())
                .andExpect(content().string(""));
        mockMvc.perform(authenticatedPost(CURRENT))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
    }

    @Test
    void controllerDependsOnlyOnThreeApplicationServices() {
        Set<Class<?>> dependencies = Set.of(
                VerificationController.class.getConstructors()[0].getParameterTypes()
        );

        assertEquals(Set.of(
                VerificationCommandService.class,
                VerificationMediaUploadService.class,
                VerificationMediaSubmissionService.class
        ), dependencies);
        assertFalse(VerificationController.class.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class
        ));
    }

    private MockHttpServletRequestBuilder authenticatedPost(String endpoint) {
        return post(endpoint).with(authentication(FirebaseBearerAuthenticationToken.authenticated(
                new AllogPrincipal(USER_ID)
        )));
    }

    private MockHttpServletRequestBuilder validUpload() {
        return authenticatedPost(UPLOAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"video/mp4\",\"sizeBytes\":123}");
    }

    private static Stream<String> endpoints() {
        return Stream.of(CURRENT, UPLOAD, SUBMIT);
    }

    private static Stream<Arguments> endpointPatterns() {
        return Stream.of(0L, -1L).flatMap(groupId -> Stream.of(
                Arguments.of("/api/v1/me/groups/%d/verifications/current", groupId),
                Arguments.of("/api/v1/me/groups/%d/verifications/current/upload-intent", groupId),
                Arguments.of("/api/v1/me/groups/%d/verifications/current/submit", groupId)
        ));
    }

    private static Stream<Arguments> mediaErrorMatrix() {
        return Stream.of(
                Arguments.of(VerificationMediaCommandException.Reason.INVALID_SIZE, 400),
                Arguments.of(VerificationMediaCommandException.Reason.MEDIA_TOO_LARGE, 413),
                Arguments.of(VerificationMediaCommandException.Reason.UNSUPPORTED_CONTENT_TYPE, 415),
                Arguments.of(VerificationMediaCommandException.Reason.CONTENT_TYPE_MISMATCH, 415),
                Arguments.of(VerificationMediaCommandException.Reason.METADATA_CONFLICT, 409),
                Arguments.of(VerificationMediaCommandException.Reason.MEDIA_NOT_BOUND, 409),
                Arguments.of(VerificationMediaCommandException.Reason.MEDIA_NOT_UPLOADED, 409),
                Arguments.of(VerificationMediaCommandException.Reason.BINDING_MISMATCH, 409),
                Arguments.of(VerificationMediaCommandException.Reason.SIZE_MISMATCH, 409)
        );
    }

    private static Stream<Arguments> storageErrorMatrix() {
        return Stream.of(
                Arguments.of(VerificationMediaStorage.StorageException.Reason.NOT_FOUND, 409),
                Arguments.of(VerificationMediaStorage.StorageException.Reason.UNAVAILABLE, 503),
                Arguments.of(VerificationMediaStorage.StorageException.Reason.CONFIGURATION, 500)
        );
    }
}

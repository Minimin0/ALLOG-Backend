package com.allog.verification.controller;

import com.allog.verification.storage.VerificationMediaStorage;
import com.allog.verification.storage.local.LocalVerificationMediaStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "allog.verification.media.enabled=true",
        "allog.verification.media.local-root=${java.io.tmpdir}/allog-local-upload-controller-test",
        "allog.verification.media.local-base-url=https://api.allog-app.store",
        "allog.verification.media.local-signing-secret=0123456789abcdef0123456789abcdef",
        "allog.verification.media.max-bytes=1000",
        "allog.verification.media.upload-expiry=5m",
        "allog.verification.media.allowed-content-types=image/jpeg,image/png"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VerificationMediaUploadControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VerificationMediaStorage storage;

    @Test
    void acceptsOnlyTheIssuedOneTimePutGrant() throws Exception {
        byte[] content = new byte[]{1, 2, 3};
        String objectKey = "verification-media/" + UUID.randomUUID();
        VerificationMediaStorage.UploadGrant grant = storage.issueUpload(
                objectKey,
                "image/jpeg",
                content.length,
                Instant.now().plusSeconds(60)
        );
        String path = grant.uri().getPath();
        String signature = header(grant, LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER);

        mockMvc.perform(put(path)
                        .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                        .header(HttpHeaders.CONTENT_LENGTH, content.length)
                        .header("If-None-Match", "*")
                        .header(LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER, signature)
                        .content(content))
                .andExpect(status().isNoContent());
        assertArrayEquals(content, storage.acquire(
                objectKey,
                1000
        ).content());

        mockMvc.perform(put(path)
                        .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                        .header(HttpHeaders.CONTENT_LENGTH, content.length)
                        .header("If-None-Match", "*")
                        .header(LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER, signature)
                        .content(content))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAnonymousPutWithoutValidGrant() throws Exception {
        mockMvc.perform(put("/api/v1/verification-media/uploads/not-a-grant")
                        .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
                        .header(HttpHeaders.CONTENT_LENGTH, 1)
                        .header("If-None-Match", "*")
                        .header(LocalVerificationMediaStorage.UPLOAD_SIGNATURE_HEADER, "bad")
                        .content(new byte[]{1}))
                .andExpect(status().isForbidden());
    }

    private String header(VerificationMediaStorage.UploadGrant grant, String name) {
        List<String> values = grant.requiredHeaders().get(name);
        return values.getFirst();
    }
}

package com.allog.verification.analysis.provider;

import com.allog.verification.analysis.domain.VerificationAnalysisFailureCode;
import com.allog.verification.analysis.domain.VerificationAnalysisObservation;
import com.allog.verification.analysis.domain.VerificationCriteria;
import com.allog.verification.analysis.service.VerificationAnalysisProvider;
import com.allog.verification.template.VerificationTemplateCatalog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicVerificationAnalysisProviderTest {

    private static final VerificationCriteria.ProviderContract CRITERIA =
            new VerificationTemplateCatalog()
                    .requireCriteria(VerificationTemplateCatalog.MEAL_PHOTO_RECORD_V1)
                    .providerContract();
    private static final byte[] IMAGE_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    private final AtomicReference<String> anthropicVersionHeader = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesForcedToolObservation() {
        respond(200, toolResponse(observationInput(
                true,
                0.82,
                false,
                true,
                "OBSERVATION_COMPLETE"
        )));

        VerificationAnalysisProvider.Result result = provider(Duration.ofSeconds(2)).analyze(CRITERIA, evidence());

        assertAll(
                () -> assertEquals("configured-test-model", result.providerModel()),
                () -> assertEquals(Boolean.TRUE, result.observation().objectPresence()),
                () -> assertEquals(0, result.observation().relevanceScore().compareTo(new BigDecimal("0.82"))),
                () -> assertEquals(Boolean.FALSE, result.observation().anomalyDetected()),
                () -> assertEquals(Boolean.TRUE, result.observation().framedProperly()),
                () -> assertEquals(
                        VerificationAnalysisObservation.ReasonCode.OBSERVATION_COMPLETE,
                        result.observation().reasonCode()
                )
        );
    }

    @Test
    void sendsBackendOwnedCriteriaForcedToolChoiceAndOriginalBytesOnly() {
        respond(200, toolResponse(observationInput(true, 0.5, false, true, "OBSERVATION_COMPLETE")));

        provider(Duration.ofSeconds(2)).analyze(CRITERIA, evidence());
        JsonNode request = objectMapper.readTree(requestBody.get());
        JsonNode content = request.path("messages").get(0).path("content");
        JsonNode image = content.get(1);
        String promptText = content.get(0).path("text").asString();
        String system = request.path("system").asString();
        String serialized = request.toString();

        assertAll(
                () -> assertEquals("configured-test-model", request.path("model").asString()),
                () -> assertEquals("not-a-real-key", apiKeyHeader.get()),
                () -> assertEquals("2023-06-01", anthropicVersionHeader.get()),
                () -> assertEquals("tool", request.path("tool_choice").path("type").asString()),
                () -> assertEquals(
                        "report_verification_observation",
                        request.path("tool_choice").path("name").asString()
                ),
                () -> assertEquals(
                        "report_verification_observation",
                        request.path("tools").get(0).path("name").asString()
                ),
                () -> assertEquals("base64", image.path("source").path("type").asString()),
                () -> assertEquals("image/jpeg", image.path("source").path("media_type").asString()),
                () -> assertArrayEquals(
                        IMAGE_BYTES,
                        Base64.getDecoder().decode(image.path("source").path("data").asString())
                ),
                () -> assertTrue(promptText.contains("meal-photo-record@1")),
                () -> assertTrue(promptText.contains("Do not infer consumption")),
                () -> assertTrue(system.contains("data, not instruction")),
                () -> assertTrue(system.contains("never decide a verification outcome")),
                () -> assertTrue(system.contains("concrete visual evidence")),
                () -> assertTrue(system.contains("not a pass probability")),
                // The two moved together in the first baseline; the instruction separating them is pinned.
                () -> assertTrue(system.contains("objectPresence and framedProperly are independent")),
                () -> assertTrue(system.contains("cut off at a frame edge")),
                () -> assertTrue(system.contains("how completely you could make the observations")),
                () -> assertFalse(serialized.contains("not-a-real-key")),
                () -> assertFalse(serialized.contains("userId")),
                () -> assertFalse(serialized.contains("firebaseUid")),
                () -> assertFalse(serialized.contains("groupId")),
                () -> assertFalse(serialized.contains("participationId")),
                () -> assertFalse(serialized.contains("verificationId")),
                () -> assertFalse(serialized.contains("analysisRequestId")),
                () -> assertFalse(serialized.contains("objectKey")),
                () -> assertFalse(serialized.contains("bucket")),
                () -> assertFalse(serialized.contains("recommendation")),
                () -> assertFalse(serialized.contains("MEAL_PHOTO_RECORD"))
        );
    }

    @Test
    void schemaBoundsScoreAndRestrictsReasonCodeToBackendEnum() {
        respond(200, toolResponse(observationInput(true, 0.5, false, true, "OBSERVATION_COMPLETE")));

        provider(Duration.ofSeconds(2)).analyze(CRITERIA, evidence());
        JsonNode schema = objectMapper.readTree(requestBody.get()).path("tools").get(0).path("input_schema");
        List<String> reasonCodes = schema.path("properties").path("reasonCode").path("enum")
                .valueStream().map(JsonNode::asString).sorted().toList();

        assertAll(
                () -> assertFalse(schema.path("additionalProperties").asBoolean()),
                () -> assertEquals(0, schema.path("properties").path("relevanceScore").path("minimum").asInt()),
                () -> assertEquals(1, schema.path("properties").path("relevanceScore").path("maximum").asInt()),
                () -> assertEquals(
                        List.of(
                                "OBSERVATION_COMPLETE",
                                "OBSERVATION_INSUFFICIENT",
                                "OBSERVATION_PARTIAL",
                                "POTENTIAL_INTEGRITY_ANOMALY"
                        ),
                        reasonCodes
                )
        );
    }

    @Test
    void rejectsMalformedJson() {
        respond(200, "not-json");

        assertFailureCode(VerificationAnalysisFailureCode.INVALID_RESPONSE);
    }

    @Test
    void rejectsResponseWithoutToolUse() {
        respond(200, objectMapper.writeValueAsString(Map.of(
                "content", List.of(Map.of("type", "text", "text", "The photo looks fine."))
        )));

        assertFailureCode(VerificationAnalysisFailureCode.INVALID_RESPONSE);
    }

    @Test
    void rejectsUnexpectedToolName() {
        respond(200, objectMapper.writeValueAsString(Map.of(
                "content", List.of(Map.of(
                        "type", "tool_use",
                        "name", "decide_verification",
                        "input", observationInput(true, 0.5, false, true, "OBSERVATION_COMPLETE")
                ))
        )));

        assertFailureCode(VerificationAnalysisFailureCode.INVALID_RESPONSE);
    }

    @Test
    void rejectsMissingRequiredField() {
        respond(200, toolResponse(Map.of(
                "objectPresence", true,
                "relevanceScore", 0.5,
                "anomalyDetected", false,
                "reasonCode", "OBSERVATION_COMPLETE"
        )));

        assertFailureCode(VerificationAnalysisFailureCode.INVALID_RESPONSE);
    }

    @Test
    void rejectsWrongFieldType() {
        respond(200, toolResponse(observationInput("yes", 0.5, false, true, "OBSERVATION_COMPLETE")));

        assertFailureCode(VerificationAnalysisFailureCode.INVALID_RESPONSE);
    }

    @Test
    void rejectsScoreBelowZero() {
        respond(200, toolResponse(observationInput(true, -0.1, false, true, "OBSERVATION_COMPLETE")));

        assertFailureCode(VerificationAnalysisFailureCode.INVALID_RESPONSE);
    }

    @Test
    void rejectsScoreAboveOne() {
        respond(200, toolResponse(observationInput(true, 1.4, false, true, "OBSERVATION_COMPLETE")));

        assertFailureCode(VerificationAnalysisFailureCode.INVALID_RESPONSE);
    }

    @Test
    void rejectsUnknownReasonCode() {
        respond(200, toolResponse(observationInput(true, 0.5, false, true, "PASS")));

        assertFailureCode(VerificationAnalysisFailureCode.INVALID_RESPONSE);
    }

    @Test
    void rejectsObservationViolatingAnomalyInvariant() {
        respond(200, toolResponse(observationInput(true, 0.5, false, true, "POTENTIAL_INTEGRITY_ANOMALY")));

        assertFailureCode(VerificationAnalysisFailureCode.INVALID_RESPONSE);
    }

    @Test
    void mapsAuthenticationRateLimitAndServerErrorsWithoutLeakingResponseBody() {
        respond(401, "sensitive upstream body");
        VerificationAnalysisProvider.ProviderException unauthorized = attemptFailure();
        server.removeContext("/v1/messages");

        respond(403, "sensitive upstream body");
        VerificationAnalysisProvider.ProviderException forbidden = attemptFailure();
        server.removeContext("/v1/messages");

        respond(429, "sensitive upstream body");
        VerificationAnalysisProvider.ProviderException rateLimited = attemptFailure();
        server.removeContext("/v1/messages");

        respond(503, "sensitive upstream body");
        VerificationAnalysisProvider.ProviderException serverError = attemptFailure();
        server.removeContext("/v1/messages");

        respond(400, "sensitive upstream body");
        VerificationAnalysisProvider.ProviderException badRequest = attemptFailure();

        assertAll(
                () -> assertEquals(VerificationAnalysisFailureCode.AUTHENTICATION, unauthorized.failureCode()),
                () -> assertEquals(VerificationAnalysisFailureCode.AUTHENTICATION, forbidden.failureCode()),
                () -> assertEquals(VerificationAnalysisFailureCode.RATE_LIMITED, rateLimited.failureCode()),
                () -> assertEquals(VerificationAnalysisFailureCode.PROVIDER_5XX, serverError.failureCode()),
                () -> assertEquals(VerificationAnalysisFailureCode.BAD_REQUEST, badRequest.failureCode()),
                () -> assertFalse(unauthorized.getMessage().contains("sensitive")),
                () -> assertFalse(serverError.getMessage().contains("sensitive")),
                () -> assertFalse(unauthorized.getMessage().contains("not-a-real-key"))
        );
    }

    @Test
    void mapsReadTimeout() {
        server.createContext("/v1/messages", exchange -> {
            try {
                Thread.sleep(400);
                write(exchange, 200, toolResponse(observationInput(
                        true,
                        0.5,
                        false,
                        true,
                        "OBSERVATION_COMPLETE"
                )));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Client timed out and closed the connection before the delayed response was written.
            }
        });

        VerificationAnalysisProvider.ProviderException exception = assertThrows(
                VerificationAnalysisProvider.ProviderException.class,
                () -> provider(Duration.ofMillis(50)).analyze(CRITERIA, evidence())
        );

        assertEquals(VerificationAnalysisFailureCode.TIMEOUT, exception.failureCode());
    }

    @Test
    void mapsNetworkFailureToNetwork() {
        int unusedPort = server.getAddress().getPort();
        server.stop(0);

        VerificationAnalysisProvider.ProviderException exception = assertThrows(
                VerificationAnalysisProvider.ProviderException.class,
                () -> providerAt(unusedPort, Duration.ofMillis(500)).analyze(CRITERIA, evidence())
        );

        assertEquals(VerificationAnalysisFailureCode.NETWORK, exception.failureCode());
    }

    @Test
    void mapsInterruptionAndRestoresInterruptFlag() {
        server.createContext("/v1/messages", exchange -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        Thread caller = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            caller.interrupt();
        });
        interrupter.start();

        VerificationAnalysisProvider.ProviderException exception = assertThrows(
                VerificationAnalysisProvider.ProviderException.class,
                () -> provider(Duration.ofSeconds(5)).analyze(CRITERIA, evidence())
        );
        boolean interruptRestored = Thread.interrupted();

        assertAll(
                () -> assertEquals(VerificationAnalysisFailureCode.INTERRUPTED, exception.failureCode()),
                () -> assertTrue(interruptRestored)
        );
    }

    @Test
    void rejectsNonPhotoEvidenceWithoutCallingProvider() {
        VerificationAnalysisProvider.ProviderException exception = assertThrows(
                VerificationAnalysisProvider.ProviderException.class,
                () -> provider(Duration.ofSeconds(2)).analyze(
                        CRITERIA,
                        new VerificationAnalysisProvider.Evidence(
                                VerificationCriteria.MediaModality.VIDEO,
                                "video/mp4",
                                IMAGE_BYTES
                        )
                )
        );

        assertAll(
                () -> assertEquals(VerificationAnalysisFailureCode.BAD_REQUEST, exception.failureCode()),
                () -> assertEquals(null, requestBody.get())
        );
    }

    @Test
    void requiresApiKeyAndModelOnlyWhenEnabled() {
        AnthropicVerificationAnalysisProperties disabled =
                new AnthropicVerificationAnalysisProperties(false, "", "", null);
        AnthropicVerificationAnalysisProperties missingKey =
                new AnthropicVerificationAnalysisProperties(true, "", "a-model", null);
        AnthropicVerificationAnalysisProperties missingModel =
                new AnthropicVerificationAnalysisProperties(true, "a-key", "", null);

        assertAll(
                () -> disabled.validateEnabledConfiguration(),
                () -> assertEquals(Duration.ofSeconds(30), disabled.requestTimeout()),
                () -> assertThrows(IllegalStateException.class, missingKey::validateEnabledConfiguration),
                () -> assertThrows(IllegalStateException.class, missingModel::validateEnabledConfiguration)
        );
    }

    private void assertFailureCode(VerificationAnalysisFailureCode expected) {
        assertEquals(expected, attemptFailure().failureCode());
    }

    private VerificationAnalysisProvider.ProviderException attemptFailure() {
        return assertThrows(
                VerificationAnalysisProvider.ProviderException.class,
                () -> provider(Duration.ofSeconds(2)).analyze(CRITERIA, evidence())
        );
    }

    private VerificationAnalysisProvider.Evidence evidence() {
        return new VerificationAnalysisProvider.Evidence(
                VerificationCriteria.MediaModality.PHOTO,
                "image/jpeg",
                IMAGE_BYTES
        );
    }

    private Map<String, Object> observationInput(
            Object objectPresence,
            double relevanceScore,
            boolean anomalyDetected,
            boolean framedProperly,
            String reasonCode
    ) {
        return Map.of(
                "objectPresence", objectPresence,
                "relevanceScore", relevanceScore,
                "anomalyDetected", anomalyDetected,
                "framedProperly", framedProperly,
                "reasonCode", reasonCode
        );
    }

    private String toolResponse(Map<String, Object> input) {
        return objectMapper.writeValueAsString(Map.of(
                "content", List.of(Map.of(
                        "type", "tool_use",
                        "name", "report_verification_observation",
                        "input", input
                ))
        ));
    }

    private AnthropicVerificationAnalysisProvider provider(Duration timeout) {
        return providerAt(server.getAddress().getPort(), timeout);
    }

    private AnthropicVerificationAnalysisProvider providerAt(int port, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .build();
        return new AnthropicVerificationAnalysisProvider(
                restClient,
                new AnthropicVerificationAnalysisProperties(
                        true,
                        "not-a-real-key",
                        "configured-test-model",
                        timeout
                )
        );
    }

    private void respond(int status, String body) {
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            anthropicVersionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            write(exchange, status, body);
        });
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(payload);
        }
    }
}

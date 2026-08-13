package com.allog.allogbe.routineverification.vision;

import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Anthropic Claude Messages API를 이용한 Vision AI 어댑터.
 * ⚠️ 이 클래스는 실제 API 키와 네트워크가 필요해 이번 구현 환경에서 실행 검증을 하지 못했다.
 * JSON 스키마 강제는 tool_choice로 report_routine_verification_analysis 도구 호출을 강제하는
 * 방식으로 구현했다 — 모델이 자유 형식 텍스트로 응답할 가능성을 원천 차단한다.
 * 응답 파싱은 {@link VisionAnalysisToolResponseParser} 에 위임한다 (네트워크 없이 단위 테스트됨).
 */
@Component
public class ClaudeVisionAnalysisClient implements VisionAnalysisClient {

	private static final URI API_URL = URI.create("https://api.anthropic.com/v1/messages");
	private static final String ANTHROPIC_VERSION = "2023-06-01";
	private static final int MAX_TOKENS = 1024;
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final VisionAnalysisToolResponseParser responseParser = new VisionAnalysisToolResponseParser();

	private final String apiKey;
	private final String model;

	public ClaudeVisionAnalysisClient(
			@Value("${allog.vision.anthropic-api-key:}") String apiKey,
			@Value("${allog.vision.model:claude-sonnet-5}") String model) {
		this.apiKey = apiKey;
		this.model = model;
	}

	@Override
	public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
		String requestBody = buildRequestBody(request);

		HttpRequest httpRequest = HttpRequest.newBuilder()
				.uri(API_URL)
				.timeout(REQUEST_TIMEOUT)
				.header("x-api-key", apiKey)
				.header("anthropic-version", ANTHROPIC_VERSION)
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
				.build();

		HttpResponse<String> response;
		try {
			response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new VisionAnalysisAttemptException("Vision API 호출 실패(IO): " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new VisionAnalysisAttemptException("Vision API 호출 중단됨", e);
		}

		if (response.statusCode() != 200) {
			throw new VisionAnalysisAttemptException(
					"Vision API 오류 응답 (status=%d): %s".formatted(response.statusCode(), response.body()));
		}

		return responseParser.parse(response.body());
	}

	private String buildRequestBody(VisionAnalysisRequest request) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("model", model);
		root.put("max_tokens", MAX_TOKENS);

		ArrayNode tools = root.putArray("tools");
		ObjectNode tool = tools.addObject();
		tool.put("name", VisionAnalysisToolSchema.TOOL_NAME);
		tool.put("description", VisionAnalysisToolSchema.TOOL_DESCRIPTION);
		tool.set("input_schema", buildInputSchema());

		ObjectNode toolChoice = root.putObject("tool_choice");
		toolChoice.put("type", "tool");
		toolChoice.put("name", VisionAnalysisToolSchema.TOOL_NAME);

		ArrayNode messages = root.putArray("messages");
		ObjectNode userMessage = messages.addObject();
		userMessage.put("role", "user");
		ArrayNode contentBlocks = userMessage.putArray("content");

		ObjectNode textBlock = contentBlocks.addObject();
		textBlock.put("type", "text");
		textBlock.put("text", VisionAnalysisPromptBuilder.build(request));

		ObjectNode imageBlock = contentBlocks.addObject();
		imageBlock.put("type", "image");
		ObjectNode source = imageBlock.putObject("source");
		source.put("type", "base64");
		source.put("media_type", request.imageMediaType());
		source.put("data", Base64.getEncoder().encodeToString(request.imageBytes()));

		return root.toString();
	}

	private ObjectNode buildInputSchema() {
		ObjectNode schema = objectMapper.createObjectNode();
		schema.put("type", "object");

		ObjectNode properties = schema.putObject("properties");
		properties.putObject("objectPresence").put("type", "boolean");

		ObjectNode detectedObjects = properties.putObject("detectedObjects");
		detectedObjects.put("type", "array");
		detectedObjects.putObject("items").put("type", "string");

		ObjectNode relevanceScore = properties.putObject("relevanceScore");
		relevanceScore.put("type", "number");
		relevanceScore.put("minimum", 0);
		relevanceScore.put("maximum", 1);

		ObjectNode anomalyFlags = properties.putObject("anomalyFlags");
		anomalyFlags.put("type", "array");
		anomalyFlags.putObject("items").put("type", "string");

		ObjectNode confidence = properties.putObject("confidence");
		confidence.put("type", "number");
		confidence.put("minimum", 0);
		confidence.put("maximum", 1);

		properties.putObject("summary").put("type", "string");
		properties.putObject("isFramedProperly").put("type", "boolean");
		properties.putObject("framingIssue").put("type", "string");

		ArrayNode required = schema.putArray("required");
		required.add("objectPresence").add("detectedObjects").add("relevanceScore")
				.add("anomalyFlags").add("confidence").add("summary").add("isFramedProperly");

		return schema;
	}
}

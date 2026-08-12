package com.allog.allogbe.routineverification.vision;

import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Messages API 응답 JSON에서 강제한 tool_use 블록을 찾아 VisionAnalysisResult 로 변환한다.
 * HTTP 전송과 분리된 순수 파싱 로직이라 네트워크 없이 단위 테스트가 가능하다.
 * 스키마를 따르지 않거나 필드가 누락/타입 불일치하면 {@link VisionAnalysisAttemptException} 을 던져
 * 상위(재시도 오케스트레이션)가 다음 시도를 하도록 한다.
 */
public class VisionAnalysisToolResponseParser {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public VisionAnalysisResult parse(String responseBody) {
		JsonNode root;
		try {
			root = MAPPER.readTree(responseBody);
		} catch (JsonProcessingException e) {
			throw new VisionAnalysisAttemptException("Vision API 응답이 유효한 JSON이 아닙니다.", e);
		}

		JsonNode content = root.path("content");
		for (JsonNode item : content) {
			if ("tool_use".equals(item.path("type").asText())
					&& VisionAnalysisToolSchema.TOOL_NAME.equals(item.path("name").asText())) {
				return mapToolInput(item.path("input"));
			}
		}
		throw new VisionAnalysisAttemptException(
				"Vision API 응답에 예상한 tool_use(" + VisionAnalysisToolSchema.TOOL_NAME + ") 블록이 없습니다.");
	}

	private VisionAnalysisResult mapToolInput(JsonNode input) {
		if (input.isMissingNode()
				|| !input.hasNonNull("objectPresence")
				|| !input.has("detectedObjects")
				|| !input.hasNonNull("relevanceScore")
				|| !input.has("anomalyFlags")
				|| !input.hasNonNull("confidence")
				|| !input.hasNonNull("summary")) {
			throw new VisionAnalysisAttemptException("Vision API 응답에 필수 필드가 누락되었습니다: " + input);
		}

		try {
			boolean objectPresence = input.get("objectPresence").asBoolean();
			List<String> detectedObjects = toStringList(input.get("detectedObjects"));
			double relevanceScore = clamp01(input.get("relevanceScore").asDouble());
			List<String> anomalyFlags = toStringList(input.get("anomalyFlags"));
			double confidence = clamp01(input.get("confidence").asDouble());
			String summary = input.get("summary").asText();

			return new VisionAnalysisResult(
					objectPresence, detectedObjects, relevanceScore, anomalyFlags, confidence, summary);
		} catch (RuntimeException e) {
			throw new VisionAnalysisAttemptException("Vision API 응답 필드 타입이 예상과 다릅니다: " + input, e);
		}
	}

	private List<String> toStringList(JsonNode arrayNode) {
		if (!arrayNode.isArray()) {
			throw new VisionAnalysisAttemptException("배열 필드가 배열 타입이 아닙니다: " + arrayNode);
		}
		List<String> result = new ArrayList<>();
		arrayNode.forEach(node -> result.add(node.asText()));
		return result;
	}

	private double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}
}

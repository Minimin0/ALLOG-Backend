package com.allog.allogbe.routineverification.vision;

import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisionAnalysisToolResponseParserTest {

	private final VisionAnalysisToolResponseParser parser = new VisionAnalysisToolResponseParser();

	@Test
	void 정상_tool_use_응답을_파싱한다() {
		String responseBody = """
				{
				  "content": [
				    { "type": "text", "text": "확인해볼게요" },
				    {
				      "type": "tool_use",
				      "name": "report_routine_verification_analysis",
				      "input": {
				        "objectPresence": true,
				        "detectedObjects": ["운동화", "매트"],
				        "relevanceScore": 0.8,
				        "anomalyFlags": [],
				        "confidence": 0.9,
				        "summary": "운동 매트와 운동화가 관찰됩니다."
				      }
				    }
				  ]
				}
				""";

		VisionAnalysisResult result = parser.parse(responseBody);

		assertThat(result.getObjectPresence()).isTrue();
		assertThat(result.getDetectedObjects()).containsExactly("운동화", "매트");
		assertThat(result.getRelevanceScore()).isEqualTo(0.8);
		assertThat(result.getAnomalyFlags()).isEmpty();
		assertThat(result.getConfidence()).isEqualTo(0.9);
		assertThat(result.getSummary()).contains("운동 매트");
	}

	@Test
	void 범위를_벗어난_점수는_0과_1_사이로_clamp된다() {
		String responseBody = """
				{
				  "content": [
				    {
				      "type": "tool_use",
				      "name": "report_routine_verification_analysis",
				      "input": {
				        "objectPresence": false,
				        "detectedObjects": [],
				        "relevanceScore": 1.5,
				        "anomalyFlags": ["워터마크 불일치"],
				        "confidence": -0.3,
				        "summary": "이상 징후가 관찰됩니다."
				      }
				    }
				  ]
				}
				""";

		VisionAnalysisResult result = parser.parse(responseBody);

		assertThat(result.getRelevanceScore()).isEqualTo(1.0);
		assertThat(result.getConfidence()).isEqualTo(0.0);
	}

	@Test
	void JSON_형식이_아니면_예외를_던진다() {
		assertThatThrownBy(() -> parser.parse("이건 JSON이 아닙니다"))
				.isInstanceOf(VisionAnalysisAttemptException.class);
	}

	@Test
	void tool_use_블록이_없으면_예외를_던진다() {
		String responseBody = """
				{ "content": [ { "type": "text", "text": "그냥 텍스트 응답입니다" } ] }
				""";

		assertThatThrownBy(() -> parser.parse(responseBody))
				.isInstanceOf(VisionAnalysisAttemptException.class);
	}

	@Test
	void 다른_이름의_도구가_호출되면_예외를_던진다() {
		String responseBody = """
				{
				  "content": [
				    { "type": "tool_use", "name": "some_other_tool", "input": {} }
				  ]
				}
				""";

		assertThatThrownBy(() -> parser.parse(responseBody))
				.isInstanceOf(VisionAnalysisAttemptException.class);
	}

	@Test
	void 필수_필드가_누락되면_예외를_던진다() {
		String responseBody = """
				{
				  "content": [
				    {
				      "type": "tool_use",
				      "name": "report_routine_verification_analysis",
				      "input": {
				        "objectPresence": true,
				        "detectedObjects": ["운동화"]
				      }
				    }
				  ]
				}
				""";

		assertThatThrownBy(() -> parser.parse(responseBody))
				.isInstanceOf(VisionAnalysisAttemptException.class);
	}

	@Test
	void 배열이어야_할_필드가_문자열이면_예외를_던진다() {
		String responseBody = """
				{
				  "content": [
				    {
				      "type": "tool_use",
				      "name": "report_routine_verification_analysis",
				      "input": {
				        "objectPresence": true,
				        "detectedObjects": "운동화",
				        "relevanceScore": 0.5,
				        "anomalyFlags": [],
				        "confidence": 0.5,
				        "summary": "요약"
				      }
				    }
				  ]
				}
				""";

		assertThatThrownBy(() -> parser.parse(responseBody))
				.isInstanceOf(VisionAnalysisAttemptException.class);
	}
}

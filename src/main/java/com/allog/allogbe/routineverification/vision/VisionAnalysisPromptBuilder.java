package com.allog.allogbe.routineverification.vision;

import java.util.List;

/**
 * Vision API에 전달할 프롬프트를 구성한다.
 * 핵심 제약: 모델이 PASS/FAIL 등 확정적 판정 표현을 쓰지 않도록 명시적으로 금지하고,
 * 이 분석이 "1차 참고 의견"일 뿐 최종 인증 여부를 확정하지 않는다는 점을 반복해서 강조한다.
 */
public final class VisionAnalysisPromptBuilder {

	private VisionAnalysisPromptBuilder() {
	}

	public static String build(VisionAnalysisRequest request) {
		String expectedObjectsText = request.expectedObjects().isEmpty()
				? "(지정되지 않음)"
				: String.join(", ", request.expectedObjects());

		return """
				당신은 ALLOG 서비스의 루틴 인증 사진/영상을 1차로 검토하는 보조 분석가입니다.

				⚠️ 매우 중요: 당신의 분석은 참고용 "1차 의견"일 뿐이며, 인증의 최종 승인/무효화를
				확정할 권한이 없습니다. 최종 판단은 반드시 상호신고와 운영자 검토를 거쳐야 합니다.
				다음 원칙을 반드시 지키세요:
				- "합격", "불합격", "PASS", "FAIL", "인증 완료", "인증 무효" 등 확정적 판정 표현을
				  절대 사용하지 마세요.
				- 당신의 역할은 이미지에서 관찰되는 사실을 객관적으로 보고하는 것이며,
				  최종 인증 여부를 결정하는 것이 아닙니다.

				[챌린지 정보]
				- 카테고리: %s
				- 루틴 설명: %s
				- 기대 객체 목록: %s

				[분석 대상]
				첨부된 이미지는 사용자가 위 루틴을 수행했다며 제출한 인증 사진(또는 영상에서
				추출한 대표 프레임)입니다.

				[요청 사항]
				반드시 %s 도구를 호출하여 아래 항목을 채워주세요. 다른 형식의 답변은 허용되지 않습니다.
				- objectPresence: 기대 객체 목록에 해당하는 대상이 이미지에서 관찰되는지 여부
				- detectedObjects: 이미지에서 실제로 관찰한 객체 목록 (기대 객체 여부와 무관하게 있는 그대로 나열)
				- relevanceScore: 이미지가 위 루틴/카테고리와 관련이 있어 보이는 정도 (0.0~1.0)
				- anomalyFlags: 조작/도용/생성형 이미지 의심, 화면 재촬영, 워터마크 불일치 등
				  이상 징후의 종류 (없으면 빈 배열)
				- confidence: 위 분석 전반에 대한 당신의 확신도 (0.0~1.0)
				- summary: 관찰 내용을 1~2문장으로 객관적으로 요약하세요 (판정 표현 금지)
				"""
				.formatted(
						request.category(),
						request.routineDescription(),
						expectedObjectsText,
						VisionAnalysisToolSchema.TOOL_NAME);
	}
}

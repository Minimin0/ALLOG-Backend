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
				%s
				[분석 대상]
				첨부된 이미지는 사용자가 위 루틴을 수행했다며 제출한 인증 사진(또는 영상에서
				추출한 대표 프레임)입니다.

				[요청 사항]
				반드시 %s 도구를 호출하여 아래 항목을 채워주세요. 다른 형식의 답변은 허용되지 않습니다.
				- objectPresence: 기대 객체 목록에 해당하는 대상이 이미지에서 관찰되는지 여부
				- detectedObjects: 이미지에서 실제로 관찰한 객체 목록 (기대 객체 여부와 무관하게 있는 그대로 나열)
				- relevanceScore: 이미지가 위 루틴/카테고리와 관련이 있어 보이는 정도 (0.0~1.0)
				- anomalyFlags: ⚠️ 이 필드는 즉시 반려 후보로 이어지는 항목입니다. 화면 재촬영 시
				  나타나는 모아레/스캔라인 패턴, 실제 서비스 소속이 아닌 타 플랫폼 워터마크, 좌우
				  반전된 텍스트처럼 이미지 안에서 직접 관찰되는 "구체적 증거"가 있을 때만 채우세요.
				  "사진이 광고/화보 같아 보인다", "스튜디오 조명 같다", "피부가 너무 매끄럽다"처럼
				  화질이 좋거나 잘 찍었다는 이유만으로 진위를 의심하는 심증성 추측은 절대 포함하지
				  마세요 — 잘 찍은 정상 사진과 조작된 사진을 혼동하면 안 됩니다. 확신이 서지 않으면
				  빈 배열을 반환하세요.
				- confidence: 위 분석 전반에 대한 당신의 확신도 (0.0~1.0)
				- summary: 관찰 내용을 1~2문장으로 객관적으로 요약하세요 (판정 표현 금지)
				- isFramedProperly: 피사체(수행 중인 루틴의 인물/제품/객체)가 프레임 안에 온전히
				  담겨 있는지 여부. 화면 밖으로 크게 잘려나갔거나 너무 멀리/작게 찍혀 식별이 어려우면 false
				- framingIssue: isFramedProperly가 false인 경우에만 문제를 1문장으로 설명하세요
				  (true인 경우 비워두세요)

				⚠️ 선명도(흐림/블러) 여부는 이미 별도의 결정론적 알고리즘으로 검증이 끝났습니다.
				이 항목을 다시 판단하거나 언급하지 마세요 — 구도(프레이밍)만 판단하세요.
				"""
				.formatted(
						request.category(),
						request.routineDescription(),
						expectedObjectsText,
						categoryGuidance(request.category()),
						VisionAnalysisToolSchema.TOOL_NAME);
	}

	/**
	 * 카테고리별 예외 규칙. SLEEP(아침 기상 인증, 거울 셀카 방식)은 제출 시간창이 이미 서버에서
	 * 검증되므로, 이미지에 침대/잠옷/시계 같은 "기상" 증거가 없다는 이유만으로 relevanceScore를
	 * 낮추지 않도록 명시한다 (캘리브레이션에서 실측: 이 안내 없이는 정상 sleep pass 사진 5장 중
	 * 4장이 relevance<0.5로 REVIEW_REQUIRED에 잘못 라우팅됨).
	 */
	private static String categoryGuidance(ChallengeCategory category) {
		if (category == ChallengeCategory.SLEEP) {
			return """

					⚠️ 카테고리 특이사항(SLEEP): 이 루틴은 아침 기상 인증이며, 제출 시각이 지정된
					시간창 안인지는 서버가 별도로 이미 검증했습니다. 이미지에 침대, 잠옷, 시계처럼
					"기상"임을 시각적으로 증명하는 요소가 보이지 않는다는 이유만으로 relevanceScore를
					낮추지 마세요. 기대 객체 목록에 맞는 거울 셀카(사람이 거울에 비쳐 보이는 상반신
					또는 전신 사진)로 확인되면 그 자체로 충분한 관련성으로 평가하세요.

					⚠️ 거울 셀카 특성상 옷에 프린트된 글자나 로고가 좌우 반전되어 보이는 것은
					지극히 정상적인 현상입니다. 좌우 반전된 텍스트가 관찰된다는 사실 하나만으로는
					anomalyFlags에 포함시키지 마세요. 모아레/스캔라인 패턴, 타 플랫폼 워터마크처럼
					거울 반사와 무관한 별도의 재촬영·도용 증거가 있을 때만 anomaly로 보고하세요.
					""";
		}
		return "";
	}
}

package com.allog.allogbe.routineverification.vision;

/** Vision API에 강제(tool_choice)할 도구 이름/설명 상수. 프롬프트와 클라이언트 양쪽에서 공유한다. */
public final class VisionAnalysisToolSchema {

	public static final String TOOL_NAME = "report_routine_verification_analysis";

	public static final String TOOL_DESCRIPTION =
			"루틴 인증 이미지에 대한 1차 참고 분석 결과를 구조화된 형태로 보고합니다. "
					+ "이 도구의 출력은 최종 인증 판정이 아니며 참고용 제안입니다.";

	private VisionAnalysisToolSchema() {
	}
}

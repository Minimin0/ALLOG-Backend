package com.allog.allogbe.routineverification.vision;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisionAnalysisPromptBuilderTest {

	@ParameterizedTest
	@EnumSource(ChallengeCategory.class)
	void 카테고리별로_기대_객체_목록과_카테고리명이_프롬프트에_반영된다(ChallengeCategory category) {
		List<String> expectedObjects = switch (category) {
			case SKINCARE -> List.of("스킨케어 제품", "거울에 비친 얼굴");
			case MEAL -> List.of("식사가 담긴 접시", "식탁");
			case EXERCISE -> List.of("운동 기구", "운동복 착용 모습");
			case SLEEP -> List.of("거울에 비친 사람");
		};

		VisionAnalysisRequest request = new VisionAnalysisRequest(
				new byte[]{1, 2, 3}, "image/jpeg", category, "테스트 루틴 설명입니다", expectedObjects);

		String prompt = VisionAnalysisPromptBuilder.build(request);

		assertThat(prompt).contains(category.name());
		assertThat(prompt).contains("테스트 루틴 설명입니다");
		for (String expectedObject : expectedObjects) {
			assertThat(prompt).contains(expectedObject);
		}
	}

	@ParameterizedTest
	@EnumSource(ChallengeCategory.class)
	void 확정적_판정_표현_금지_지시가_모든_카테고리에서_포함된다(ChallengeCategory category) {
		VisionAnalysisRequest request = new VisionAnalysisRequest(
				new byte[]{1}, "image/jpeg", category, "설명", List.of("객체"));

		String prompt = VisionAnalysisPromptBuilder.build(request);

		assertThat(prompt).contains("PASS", "FAIL", "1차 의견", "확정");
	}

	@org.junit.jupiter.api.Test
	void 기대_객체가_비어있으면_안내_문구로_대체된다() {
		VisionAnalysisRequest request = new VisionAnalysisRequest(
				new byte[]{1}, "image/jpeg", ChallengeCategory.MEAL, "설명", List.of());

		String prompt = VisionAnalysisPromptBuilder.build(request);

		assertThat(prompt).contains("(지정되지 않음)");
	}
}

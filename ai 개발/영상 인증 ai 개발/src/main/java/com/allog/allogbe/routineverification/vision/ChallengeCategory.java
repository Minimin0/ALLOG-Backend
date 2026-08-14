package com.allog.allogbe.routineverification.vision;

/**
 * 스킨케어/식사/운동/수면 카테고리. 원래는 Challenge/ChallengeTemplate 도메인(미구현,
 * 연동 필요 지점)의 필드이지만, Vision 프롬프트 구성에 필요해 이 패키지에 임시로 둔다.
 * Challenge 도메인 구현 후에는 그쪽 값을 그대로 사용하도록 교체되어야 한다.
 */
public enum ChallengeCategory {
	SKINCARE,
	MEAL,
	EXERCISE,
	SLEEP
}

package com.allog.allogbe.routineverification.insight;

import com.allog.allogbe.routineverification.repository.RoutineVerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ④ 진행 페이스 코칭(다른 담당자 영역)이 인증 1차 분류 결과를 참조할 수 있도록 노출하는
 * 읽기 전용 조회 인터페이스. 코칭 로직 자체(추천/알림 등)는 이 모듈에서 구현하지 않는다 — 스코프 경계 준수.
 */
@Service
@Transactional(readOnly = true)
public class RoutineVerificationInsightsQueryService {

	private final RoutineVerificationRepository repository;

	public RoutineVerificationInsightsQueryService(RoutineVerificationRepository repository) {
		this.repository = repository;
	}

	public List<RoutineVerificationSummary> findRecentSummaries(Long userId) {
		return repository.findTop20ByUserIdOrderBySubmittedAtDesc(userId).stream()
				.map(v -> new RoutineVerificationSummary(
						v.getId(), v.getChallengeId(), v.getAiClassification(), v.getReviewStatus(),
						v.isCountedInScore(), v.getSubmittedAt()))
				.toList();
	}
}

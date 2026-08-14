package com.allog.allogbe.routineverification.repository;

import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.RoutineVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoutineVerificationRepository extends JpaRepository<RoutineVerification, Long> {

	List<RoutineVerification> findByReviewStatus(ReviewStatus status);

	/** ④ 코칭 기능 등 외부 조회용 — 최근 제출 이력(최신순). */
	List<RoutineVerification> findTop20ByUserIdOrderBySubmittedAtDesc(Long userId);
}

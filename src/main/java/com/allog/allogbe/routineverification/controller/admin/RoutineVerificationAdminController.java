package com.allog.allogbe.routineverification.controller.admin;

import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.dto.RoutineVerificationAdminUpdateRequest;
import com.allog.allogbe.routineverification.dto.RoutineVerificationAdminUpdateResponse;
import com.allog.allogbe.routineverification.dto.RoutineVerificationQueueItemResponse;
import com.allog.allogbe.routineverification.service.RoutineVerificationAdminReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 운영자 전용 API. reviewedBy 는 인증/인가 도메인이 없어 임시로 요청 바디로 받는다 (연동 필요 지점).
 * ⚠️ "권한 없음(운영자만 접근 가능)" 검증은 인증/인가 도메인이 없어 이번 스코프에서 구현하지 못했다.
 */
@RestController
@RequestMapping("/api/v1/admin/verifications")
public class RoutineVerificationAdminController {

	private final RoutineVerificationAdminReviewService adminReviewService;

	public RoutineVerificationAdminController(RoutineVerificationAdminReviewService adminReviewService) {
		this.adminReviewService = adminReviewService;
	}

	/** reviewPriority(HIGH 우선) 기준으로 정렬된 검토 큐. 기본은 FLAGGED_FOR_REVIEW 건만 조회한다. */
	@GetMapping
	public ResponseEntity<List<RoutineVerificationQueueItemResponse>> queue(
			@RequestParam(defaultValue = "FLAGGED_FOR_REVIEW") ReviewStatus status) {
		return ResponseEntity.ok(adminReviewService.listQueue(status));
	}

	/** reviewStatus 를 VALID_CONFIRMED/INVALIDATED/RESUBMIT_REQUESTED 로 최종 확정하는 유일한 경로. */
	@PatchMapping("/{id}")
	public ResponseEntity<RoutineVerificationAdminUpdateResponse> confirm(
			@PathVariable Long id, @RequestBody RoutineVerificationAdminUpdateRequest request) {
		return ResponseEntity.ok(adminReviewService.confirmFinalStatus(id, request));
	}
}

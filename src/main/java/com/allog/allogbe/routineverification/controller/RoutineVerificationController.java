package com.allog.allogbe.routineverification.controller;

import com.allog.allogbe.routineverification.domain.RoutineVerification;
import com.allog.allogbe.routineverification.domain.SubmissionType;
import com.allog.allogbe.routineverification.dto.RoutineVerificationDetailMapper;
import com.allog.allogbe.routineverification.dto.RoutineVerificationDetailResponse;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitCommand;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitResponse;
import com.allog.allogbe.routineverification.exception.RoutineVerificationNotFoundException;
import com.allog.allogbe.routineverification.repository.RoutineVerificationRepository;
import com.allog.allogbe.routineverification.service.RoutineVerificationSubmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

/**
 * 사용자용 인증 제출/조회 API (STAGE8).
 * userId 는 인증/인가 도메인이 없어 임시로 요청 파라미터로 받는다 (연동 필요 지점).
 * (신고 도메인 미구현 — POST /verifications/{id}/reports 는 STAGE1 확인 결과 대상 도메인이
 * 존재하지 않아 구현하지 않았다. 연동 필요 지점으로 별도 보고.)
 */
@RestController
@RequestMapping("/api/v1")
public class RoutineVerificationController {

	private final RoutineVerificationSubmissionService submissionService;
	private final RoutineVerificationRepository repository;

	public RoutineVerificationController(RoutineVerificationSubmissionService submissionService,
			RoutineVerificationRepository repository) {
		this.submissionService = submissionService;
		this.repository = repository;
	}

	@PostMapping(value = "/challenges/{challengeId}/verifications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<RoutineVerificationSubmitResponse> submit(
			@PathVariable Long challengeId,
			@RequestParam Long userId,
			@RequestParam Long participationId,
			@RequestParam SubmissionType submissionType,
			@RequestPart MultipartFile file) {

		RoutineVerificationSubmitCommand command = new RoutineVerificationSubmitCommand(
				userId, challengeId, participationId, submissionType, file, LocalDateTime.now());

		RoutineVerificationSubmitResponse response = submissionService.submit(command);

		return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
	}

	@GetMapping("/verifications/{id}")
	public ResponseEntity<RoutineVerificationDetailResponse> getDetail(@PathVariable Long id) {
		RoutineVerification verification = repository.findById(id)
				.orElseThrow(() -> new RoutineVerificationNotFoundException(id));

		return ResponseEntity.ok(RoutineVerificationDetailMapper.toResponse(verification));
	}
}

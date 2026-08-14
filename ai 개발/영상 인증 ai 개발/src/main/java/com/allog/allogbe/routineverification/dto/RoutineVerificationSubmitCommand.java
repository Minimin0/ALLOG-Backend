package com.allog.allogbe.routineverification.dto;

import com.allog.allogbe.routineverification.domain.SubmissionType;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

/**
 * userId 는 임시로 요청 파라미터에서 받는다 — 실제로는 인증 주체(SecurityContext)에서
 * 가져와야 하며, 이번 스코프에는 인증/인가 도메인이 없어 대체했다 (연동 필요 지점).
 */
public record RoutineVerificationSubmitCommand(
		Long userId,
		Long challengeId,
		Long participationId,
		SubmissionType submissionType,
		MultipartFile file,
		LocalDateTime submittedAt
) {
}

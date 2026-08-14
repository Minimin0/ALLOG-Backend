package com.allog.allogbe.routineverification.service;

import com.allog.allogbe.routineverification.domain.ReviewPriority;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.RoutineVerification;
import com.allog.allogbe.routineverification.dto.RoutineVerificationAdminUpdateRequest;
import com.allog.allogbe.routineverification.dto.RoutineVerificationAdminUpdateResponse;
import com.allog.allogbe.routineverification.dto.RoutineVerificationDetailMapper;
import com.allog.allogbe.routineverification.dto.RoutineVerificationQueueItemResponse;
import com.allog.allogbe.routineverification.event.RoutineVerificationScoreCountingChangedEvent;
import com.allog.allogbe.routineverification.exception.InvalidAdminReviewStatusException;
import com.allog.allogbe.routineverification.exception.RoutineVerificationNotFoundException;
import com.allog.allogbe.routineverification.repository.RoutineVerificationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 운영자 전용 최종 확정 유스케이스. reviewStatus 를 VALID_CONFIRMED/INVALIDATED/RESUBMIT_REQUESTED 로
 * 전환하는 코드 경로는 이 서비스(및 이 서비스가 호출하는 RoutineVerification.confirmFinalReview)가 유일하다.
 * AI/규칙엔진은 이 상태들에 절대 도달하지 않는다.
 */
@Service
public class RoutineVerificationAdminReviewService {

	private static final Set<ReviewStatus> ALLOWED_FINAL_STATUSES =
			EnumSet.of(ReviewStatus.VALID_CONFIRMED, ReviewStatus.INVALIDATED, ReviewStatus.RESUBMIT_REQUESTED);

	private final RoutineVerificationRepository repository;
	private final ApplicationEventPublisher eventPublisher;

	public RoutineVerificationAdminReviewService(RoutineVerificationRepository repository,
			ApplicationEventPublisher eventPublisher) {
		this.repository = repository;
		this.eventPublisher = eventPublisher;
	}

	public List<RoutineVerificationQueueItemResponse> listQueue(ReviewStatus status) {
		return repository.findByReviewStatus(status).stream()
				.sorted(Comparator
						.comparing((RoutineVerification v) -> v.getReviewPriority() == ReviewPriority.HIGH ? 0 : 1)
						.thenComparing(RoutineVerification::getSubmittedAt))
				.map(RoutineVerificationDetailMapper::toQueueItem)
				.toList();
	}

	public RoutineVerificationAdminUpdateResponse confirmFinalStatus(Long id,
			RoutineVerificationAdminUpdateRequest request) {
		if (!ALLOWED_FINAL_STATUSES.contains(request.targetStatus())) {
			throw new InvalidAdminReviewStatusException(
					"운영자 확정 상태로 허용되지 않습니다: " + request.targetStatus());
		}

		RoutineVerification verification = repository.findById(id)
				.orElseThrow(() -> new RoutineVerificationNotFoundException(id));

		boolean countedBefore = verification.isCountedInScore();
		verification.confirmFinalReview(request.targetStatus(), request.reviewedBy());
		RoutineVerification saved = repository.save(verification);

		if (saved.isCountedInScore() != countedBefore) {
			// STAGE9 연동 지점: 운영자 확정으로 집계 반영 여부가 바뀌면(부여/회수) 달성률 서비스가
			// 구독할 수 있도록 이벤트만 발행한다. 실제 집계/회수 로직은 이 모듈의 책임이 아니다.
			eventPublisher.publishEvent(new RoutineVerificationScoreCountingChangedEvent(
					saved.getId(), saved.getUserId(), saved.getChallengeId(), saved.getParticipationId(),
					saved.isCountedInScore(), LocalDateTime.now()));
		}

		return new RoutineVerificationAdminUpdateResponse(
				saved.getId(), saved.getReviewStatus(), saved.getReviewedAt(), saved.getReviewedBy());
	}
}

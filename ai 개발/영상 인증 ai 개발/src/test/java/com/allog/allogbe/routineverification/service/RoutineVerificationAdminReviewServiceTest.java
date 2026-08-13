package com.allog.allogbe.routineverification.service;

import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.RoutineVerification;
import com.allog.allogbe.routineverification.domain.SubmissionType;
import com.allog.allogbe.routineverification.dto.RoutineVerificationAdminUpdateRequest;
import com.allog.allogbe.routineverification.dto.RoutineVerificationAdminUpdateResponse;
import com.allog.allogbe.routineverification.dto.RoutineVerificationQueueItemResponse;
import com.allog.allogbe.routineverification.event.RoutineVerificationScoreCountingChangedEvent;
import com.allog.allogbe.routineverification.exception.InvalidAdminReviewStatusException;
import com.allog.allogbe.routineverification.exception.RoutineVerificationNotFoundException;
import com.allog.allogbe.routineverification.repository.RoutineVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutineVerificationAdminReviewServiceTest {

	@Mock
	private RoutineVerificationRepository repository;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	private RoutineVerificationAdminReviewService service;

	@BeforeEach
	void setUp() {
		service = new RoutineVerificationAdminReviewService(repository, eventPublisher);
	}

	private RoutineVerification newVerification(LocalDateTime submittedAt) {
		return new RoutineVerification(100L, 1L, 200L, SubmissionType.PHOTO, "https://x.jpg",
				submittedAt, new MetadataCheck(true, false, null));
	}

	@Test
	void 유효한_최종상태로_확정하면_reviewedAt_reviewedBy가_기록된다() {
		RoutineVerification verification = newVerification(LocalDateTime.of(2026, 8, 8, 8, 0));
		when(repository.findById(1L)).thenReturn(Optional.of(verification));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		RoutineVerificationAdminUpdateResponse response = service.confirmFinalStatus(1L,
				new RoutineVerificationAdminUpdateRequest(ReviewStatus.VALID_CONFIRMED, 999L, "정상 확인"));

		assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.VALID_CONFIRMED);
		assertThat(response.reviewedBy()).isEqualTo(999L);
		assertThat(response.reviewedAt()).isNotNull();

		ArgumentCaptor<RoutineVerificationScoreCountingChangedEvent> eventCaptor =
				ArgumentCaptor.forClass(RoutineVerificationScoreCountingChangedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().countedInScore()).isTrue();
	}

	@Test
	void 이미_카운트되지_않은_건을_INVALIDATED로_확정해도_집계변경_이벤트는_발행되지_않는다() {
		RoutineVerification verification = newVerification(LocalDateTime.of(2026, 8, 8, 8, 0));
		when(repository.findById(1L)).thenReturn(Optional.of(verification));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.confirmFinalStatus(1L, new RoutineVerificationAdminUpdateRequest(ReviewStatus.INVALIDATED, 999L, null));

		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void 이미_카운트된_AUTO_VALID_건을_INVALIDATED로_확정하면_집계회수_이벤트가_발행된다() {
		RoutineVerification verification = newVerification(LocalDateTime.of(2026, 8, 8, 8, 0));
		verification.applyClassificationResult(verification.getMetadataCheck(), null, null,
				com.allog.allogbe.routineverification.domain.AiClassification.PASS,
				ReviewStatus.AUTO_VALID, com.allog.allogbe.routineverification.domain.ReviewPriority.NORMAL, true);
		when(repository.findById(1L)).thenReturn(Optional.of(verification));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.confirmFinalStatus(1L, new RoutineVerificationAdminUpdateRequest(ReviewStatus.INVALIDATED, 999L, "도용 신고 확인"));

		ArgumentCaptor<RoutineVerificationScoreCountingChangedEvent> eventCaptor =
				ArgumentCaptor.forClass(RoutineVerificationScoreCountingChangedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().countedInScore()).isFalse();
	}

	@Test
	void AUTO_VALID_같은_비허용_상태로는_확정할_수_없다() {
		assertThatThrownBy(() -> service.confirmFinalStatus(1L,
				new RoutineVerificationAdminUpdateRequest(ReviewStatus.AUTO_VALID, 999L, null)))
				.isInstanceOf(InvalidAdminReviewStatusException.class);

		verify(repository, never()).findById(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void 존재하지_않는_id면_NotFound_예외를_던진다() {
		when(repository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.confirmFinalStatus(404L,
				new RoutineVerificationAdminUpdateRequest(ReviewStatus.INVALIDATED, 999L, null)))
				.isInstanceOf(RoutineVerificationNotFoundException.class);
	}

	@Test
	void 이미_확정된_건은_재확정할_수_없다() {
		RoutineVerification verification = newVerification(LocalDateTime.of(2026, 8, 8, 8, 0));
		verification.confirmFinalReview(ReviewStatus.VALID_CONFIRMED, 1L);
		when(repository.findById(1L)).thenReturn(Optional.of(verification));

		assertThatThrownBy(() -> service.confirmFinalStatus(1L,
				new RoutineVerificationAdminUpdateRequest(ReviewStatus.INVALIDATED, 999L, null)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 큐는_HIGH_우선순위가_먼저_오고_그_다음은_제출시각_순이다() {
		RoutineVerification normalOlder = newVerification(LocalDateTime.of(2026, 8, 8, 6, 0));
		RoutineVerification highNewer = newVerification(LocalDateTime.of(2026, 8, 8, 9, 0));
		RoutineVerification highOlder = newVerification(LocalDateTime.of(2026, 8, 8, 7, 0));
		applyPriority(normalOlder, com.allog.allogbe.routineverification.domain.ReviewPriority.NORMAL);
		applyPriority(highNewer, com.allog.allogbe.routineverification.domain.ReviewPriority.HIGH);
		applyPriority(highOlder, com.allog.allogbe.routineverification.domain.ReviewPriority.HIGH);

		when(repository.findByReviewStatus(ReviewStatus.FLAGGED_FOR_REVIEW))
				.thenReturn(List.of(normalOlder, highNewer, highOlder));

		List<RoutineVerificationQueueItemResponse> queue = service.listQueue(ReviewStatus.FLAGGED_FOR_REVIEW);

		assertThat(queue).extracting(RoutineVerificationQueueItemResponse::submittedAt)
				.containsExactly(
						LocalDateTime.of(2026, 8, 8, 7, 0),
						LocalDateTime.of(2026, 8, 8, 9, 0),
						LocalDateTime.of(2026, 8, 8, 6, 0));
	}

	private void applyPriority(RoutineVerification verification,
			com.allog.allogbe.routineverification.domain.ReviewPriority priority) {
		verification.applyClassificationResult(verification.getMetadataCheck(), null, null,
				com.allog.allogbe.routineverification.domain.AiClassification.REVIEW_REQUIRED,
				ReviewStatus.FLAGGED_FOR_REVIEW, priority, false);
	}
}

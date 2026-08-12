package com.allog.allogbe.routineverification.controller.admin;

import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.dto.RoutineVerificationAdminUpdateRequest;
import com.allog.allogbe.routineverification.dto.RoutineVerificationAdminUpdateResponse;
import com.allog.allogbe.routineverification.dto.RoutineVerificationQueueItemResponse;
import com.allog.allogbe.routineverification.service.RoutineVerificationAdminReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutineVerificationAdminControllerTest {

	@Mock
	private RoutineVerificationAdminReviewService adminReviewService;

	private RoutineVerificationAdminController controller;

	@BeforeEach
	void setUp() {
		controller = new RoutineVerificationAdminController(adminReviewService);
	}

	@Test
	void queue는_서비스에_상태를_그대로_위임한다() {
		List<RoutineVerificationQueueItemResponse> expected = List.of();
		when(adminReviewService.listQueue(ReviewStatus.FLAGGED_FOR_REVIEW)).thenReturn(expected);

		ResponseEntity<List<RoutineVerificationQueueItemResponse>> response =
				controller.queue(ReviewStatus.FLAGGED_FOR_REVIEW);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(expected);
	}

	@Test
	void confirm은_서비스_응답을_그대로_반환한다() {
		RoutineVerificationAdminUpdateRequest request =
				new RoutineVerificationAdminUpdateRequest(ReviewStatus.VALID_CONFIRMED, 999L, "확인");
		RoutineVerificationAdminUpdateResponse expected = new RoutineVerificationAdminUpdateResponse(
				1L, ReviewStatus.VALID_CONFIRMED, LocalDateTime.of(2026, 8, 8, 10, 0), 999L);
		when(adminReviewService.confirmFinalStatus(1L, request)).thenReturn(expected);

		ResponseEntity<RoutineVerificationAdminUpdateResponse> response = controller.confirm(1L, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(expected);
	}
}

package com.allog.allogbe.routineverification.controller;

import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.RoutineVerification;
import com.allog.allogbe.routineverification.domain.SubmissionType;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitCommand;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitResponse;
import com.allog.allogbe.routineverification.exception.RoutineVerificationNotFoundException;
import com.allog.allogbe.routineverification.repository.RoutineVerificationRepository;
import com.allog.allogbe.routineverification.service.RoutineVerificationSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutineVerificationControllerTest {

	@Mock
	private RoutineVerificationSubmissionService submissionService;
	@Mock
	private RoutineVerificationRepository repository;

	private RoutineVerificationController controller;

	@BeforeEach
	void setUp() {
		controller = new RoutineVerificationController(submissionService, repository);
	}

	@Test
	void submit은_202와_함께_서비스_응답을_그대로_반환한다() {
		MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});
		RoutineVerificationSubmitResponse serviceResponse =
				new RoutineVerificationSubmitResponse(1L, ReviewStatus.PENDING, "접수됨");
		when(submissionService.submit(any())).thenReturn(serviceResponse);

		ResponseEntity<RoutineVerificationSubmitResponse> response =
				controller.submit(1L, 100L, 200L, SubmissionType.PHOTO, file);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getBody()).isEqualTo(serviceResponse);

		ArgumentCaptor<RoutineVerificationSubmitCommand> captor =
				ArgumentCaptor.forClass(RoutineVerificationSubmitCommand.class);
		verify(submissionService).submit(captor.capture());
		assertThat(captor.getValue().challengeId()).isEqualTo(1L);
		assertThat(captor.getValue().userId()).isEqualTo(100L);
		assertThat(captor.getValue().participationId()).isEqualTo(200L);
		assertThat(captor.getValue().submissionType()).isEqualTo(SubmissionType.PHOTO);
	}

	@Test
	void getDetail은_존재하면_200과_상세정보를_반환한다() {
		RoutineVerification verification = new RoutineVerification(100L, 1L, 200L, SubmissionType.PHOTO,
				"https://x.jpg", LocalDateTime.of(2026, 8, 8, 8, 0), new MetadataCheck(true, false, null));
		when(repository.findById(1L)).thenReturn(Optional.of(verification));

		ResponseEntity<?> response = controller.getDetail(1L);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
	}

	@Test
	void getDetail은_존재하지_않으면_NotFound_예외를_던진다() {
		when(repository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.getDetail(999L))
				.isInstanceOf(RoutineVerificationNotFoundException.class);
	}
}

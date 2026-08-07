package com.allog.allogbe.routineverification.service;

import com.allog.allogbe.routineverification.classification.ClassificationDecision;
import com.allog.allogbe.routineverification.classification.RoutineVerificationClassificationOutput;
import com.allog.allogbe.routineverification.classification.RoutineVerificationClassificationPipeline;
import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.domain.ReviewPriority;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.RoutineVerification;
import com.allog.allogbe.routineverification.domain.SubmissionType;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitCommand;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitResponse;
import com.allog.allogbe.routineverification.event.RoutineVerificationClassifiedEvent;
import com.allog.allogbe.routineverification.event.RoutineVerificationScoreCountingChangedEvent;
import com.allog.allogbe.routineverification.exception.OutsideVerificationTimeWindowException;
import com.allog.allogbe.routineverification.repository.RoutineVerificationRepository;
import org.springframework.context.ApplicationEventPublisher;
import com.allog.allogbe.routineverification.storage.RoutineVerificationMediaStoragePort;
import com.allog.allogbe.routineverification.vision.ChallengeVisionContext;
import com.allog.allogbe.routineverification.vision.ChallengeVisionContextProvider;
import com.allog.allogbe.routineverification.vision.ChallengeCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutineVerificationSubmissionServiceTest {

	@Mock
	private RoutineVerificationSubmissionGate gate;
	@Mock
	private RoutineVerificationMediaStoragePort mediaStorage;
	@Mock
	private RoutineVerificationRepository repository;
	@Mock
	private ChallengeVisionContextProvider visionContextProvider;
	@Mock
	private RoutineVerificationClassificationPipeline pipeline;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	private RoutineVerificationSubmissionService service;

	@BeforeEach
	void setUp() {
		service = new RoutineVerificationSubmissionService(
				gate, mediaStorage, repository, visionContextProvider, pipeline, eventPublisher);
		org.mockito.Mockito.lenient().when(repository.save(any()))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	private MockMultipartFile photoFile() throws IOException {
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return new MockMultipartFile("file", "photo.jpg", "image/jpeg", out.toByteArray());
	}

	@Test
	void PHOTO_제출은_게이트_저장_분류를_순서대로_수행한다() throws IOException {
		RoutineVerificationSubmitCommand command = new RoutineVerificationSubmitCommand(
				100L, 1L, 200L, SubmissionType.PHOTO, photoFile(), LocalDateTime.of(2026, 8, 8, 8, 0));

		when(gate.validate(any())).thenReturn(new MetadataCheck(true, false, null));
		when(mediaStorage.store(any())).thenReturn("https://cdn.allog.dev/x.jpg");
		when(visionContextProvider.getContext(1L)).thenReturn(
				new ChallengeVisionContext(ChallengeCategory.EXERCISE, "운동 인증", List.of("운동화")));
		when(pipeline.process(any())).thenReturn(new RoutineVerificationClassificationOutput(
				new MetadataCheck(true, false, null), null,
				new ClassificationDecision(AiClassification.PASS, ReviewStatus.AUTO_VALID, ReviewPriority.NORMAL, true)));

		RoutineVerificationSubmitResponse response = service.submit(command);

		assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.AUTO_VALID);
		verify(mediaStorage, times(1)).store(any());
		verify(pipeline, times(1)).process(any());

		ArgumentCaptor<RoutineVerification> captor = ArgumentCaptor.forClass(RoutineVerification.class);
		verify(repository, times(2)).save(captor.capture());
		assertThat(captor.getValue().getReviewStatus()).isEqualTo(ReviewStatus.AUTO_VALID);
		assertThat(captor.getValue().isCountedInScore()).isTrue();

		ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
		verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getAllValues())
				.anyMatch(RoutineVerificationClassifiedEvent.class::isInstance)
				.anyMatch(event -> event instanceof RoutineVerificationScoreCountingChangedEvent counted
						&& counted.countedInScore());
	}

	@Test
	void VIDEO_제출은_아직_배선되지_않아_REVIEW_REQUIRED로_보수적_처리된다() throws IOException {
		MockMultipartFile file = new MockMultipartFile("file", "video.mp4", "video/mp4", new byte[]{1, 2, 3});
		RoutineVerificationSubmitCommand command = new RoutineVerificationSubmitCommand(
				100L, 1L, 200L, SubmissionType.VIDEO, file, LocalDateTime.of(2026, 8, 8, 8, 0));

		when(gate.validate(any())).thenReturn(new MetadataCheck(true, false, null));
		when(mediaStorage.store(any())).thenReturn("https://cdn.allog.dev/x.mp4");

		RoutineVerificationSubmitResponse response = service.submit(command);

		assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.FLAGGED_FOR_REVIEW);
		verifyNoInteractions(pipeline, visionContextProvider);

		verify(eventPublisher, times(1)).publishEvent(any(RoutineVerificationClassifiedEvent.class));
		verify(eventPublisher, never()).publishEvent(any(RoutineVerificationScoreCountingChangedEvent.class));
	}

	@Test
	void APP_RECORD_제출은_아직_배선되지_않아_REVIEW_REQUIRED로_보수적_처리된다() throws IOException {
		MockMultipartFile file = new MockMultipartFile("file", "record.json", "application/json", new byte[]{1});
		RoutineVerificationSubmitCommand command = new RoutineVerificationSubmitCommand(
				100L, 1L, 200L, SubmissionType.APP_RECORD, file, LocalDateTime.of(2026, 8, 8, 8, 0));

		when(gate.validate(any())).thenReturn(new MetadataCheck(true, false, null));
		when(mediaStorage.store(any())).thenReturn("https://cdn.allog.dev/x.json");

		RoutineVerificationSubmitResponse response = service.submit(command);

		assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.FLAGGED_FOR_REVIEW);
		verifyNoInteractions(pipeline, visionContextProvider);
	}

	@Test
	void 게이트가_거부하면_미디어_저장도_엔티티_저장도_발생하지_않는다() throws IOException {
		RoutineVerificationSubmitCommand command = new RoutineVerificationSubmitCommand(
				100L, 1L, 200L, SubmissionType.PHOTO, photoFile(), LocalDateTime.of(2026, 8, 8, 23, 0));

		when(gate.validate(any())).thenThrow(new OutsideVerificationTimeWindowException("시간 범위 밖"));

		assertThatThrownBy(() -> service.submit(command))
				.isInstanceOf(OutsideVerificationTimeWindowException.class);

		verifyNoInteractions(mediaStorage, repository, pipeline, visionContextProvider, eventPublisher);
	}
}

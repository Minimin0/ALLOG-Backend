package com.allog.allogbe.routineverification.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutineVerificationFrameCaptureServiceTest {

	@Mock
	private VideoFrameExtractor extractor;

	private RoutineVerificationFrameCaptureService service;

	@BeforeEach
	void setUp() {
		service = new RoutineVerificationFrameCaptureService(extractor);
	}

	@Test
	void 정상_영상은_중간_지점에서_1회만에_추출된다() {
		Path videoPath = Path.of("video.mp4");
		Duration duration = Duration.ofSeconds(100);
		Path expected = Path.of("/tmp/frame-mid.jpg");

		when(extractor.extractFrame(eq(videoPath), eq(Duration.ofSeconds(50)))).thenReturn(expected);

		Path result = service.captureRepresentativeFrame(videoPath, duration);

		assertThat(result).isEqualTo(expected);
		verify(extractor, times(1)).extractFrame(any(), any());
	}

	@Test
	void 중간_지점_실패시_앞_뒤_지점_순서로_재시도한다() {
		Path videoPath = Path.of("video.mp4");
		Duration duration = Duration.ofSeconds(100);
		Path expected = Path.of("/tmp/frame-back.jpg");

		when(extractor.extractFrame(eq(videoPath), eq(Duration.ofSeconds(50))))
				.thenThrow(new FrameExtractionAttemptException("mid failed"));
		when(extractor.extractFrame(eq(videoPath), eq(Duration.ofSeconds(25))))
				.thenThrow(new FrameExtractionAttemptException("front failed"));
		when(extractor.extractFrame(eq(videoPath), eq(Duration.ofSeconds(75))))
				.thenReturn(expected);

		Path result = service.captureRepresentativeFrame(videoPath, duration);

		assertThat(result).isEqualTo(expected);

		InOrder inOrder = inOrder(extractor);
		inOrder.verify(extractor).extractFrame(videoPath, Duration.ofSeconds(50));
		inOrder.verify(extractor).extractFrame(videoPath, Duration.ofSeconds(25));
		inOrder.verify(extractor).extractFrame(videoPath, Duration.ofSeconds(75));
	}

	@Test
	void 손상된_파일은_3회_모두_실패하면_FrameCaptureException을_던진다() {
		Path videoPath = Path.of("corrupt.mp4");
		Duration duration = Duration.ofSeconds(60);

		when(extractor.extractFrame(eq(videoPath), any()))
				.thenThrow(new FrameExtractionAttemptException("decode failed"));

		assertThatThrownBy(() -> service.captureRepresentativeFrame(videoPath, duration))
				.isInstanceOf(FrameCaptureException.class)
				.hasCauseInstanceOf(FrameExtractionAttemptException.class);

		verify(extractor, times(3)).extractFrame(eq(videoPath), any());
	}

	@Test
	void 매우_짧은_영상도_음수_타임스탬프_없이_처리된다() {
		Path videoPath = Path.of("short.mp4");
		Duration duration = Duration.ofSeconds(2);
		Path expected = Path.of("/tmp/frame.jpg");

		when(extractor.extractFrame(eq(videoPath), any())).thenReturn(expected);

		Path result = service.captureRepresentativeFrame(videoPath, duration);

		assertThat(result).isEqualTo(expected);
		ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
		verify(extractor).extractFrame(eq(videoPath), captor.capture());
		assertThat(captor.getValue()).isGreaterThanOrEqualTo(Duration.ZERO);
	}
}

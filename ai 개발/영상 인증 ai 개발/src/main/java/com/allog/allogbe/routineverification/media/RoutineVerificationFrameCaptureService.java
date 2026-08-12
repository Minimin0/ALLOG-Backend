package com.allog.allogbe.routineverification.media;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * VIDEO 제출에서만 사용되는 대표 프레임 캡처 오케스트레이션 (STAGE4).
 * 1차 시도는 영상 중간 지점, 실패 시 앞 지점(1/4) -> 뒤 지점(3/4) 순으로 최대 3회 재시도한다.
 * 실제 디코딩(ffmpeg 등)은 {@link VideoFrameExtractor} 구현체에 위임한다.
 */
@Component
public class RoutineVerificationFrameCaptureService {

	private static final int MAX_ATTEMPTS = 3;

	private final VideoFrameExtractor extractor;

	public RoutineVerificationFrameCaptureService(VideoFrameExtractor extractor) {
		this.extractor = extractor;
	}

	public Path captureRepresentativeFrame(Path videoPath, Duration videoDuration) {
		List<Duration> candidateTimestamps = candidateTimestamps(videoDuration);
		FrameExtractionAttemptException lastFailure = null;

		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			try {
				return extractor.extractFrame(videoPath, candidateTimestamps.get(attempt));
			} catch (FrameExtractionAttemptException e) {
				lastFailure = e;
			}
		}

		throw new FrameCaptureException(
				"영상에서 프레임 추출에 실패했습니다 (%d회 시도): %s".formatted(MAX_ATTEMPTS, videoPath), lastFailure);
	}

	/** [중간, 앞(1/4), 뒤(3/4)] 순서. 음수가 나오지 않도록 0으로 클램프한다. */
	private List<Duration> candidateTimestamps(Duration videoDuration) {
		Duration mid = clampNonNegative(videoDuration.dividedBy(2));
		Duration front = clampNonNegative(videoDuration.dividedBy(4));
		Duration back = clampNonNegative(videoDuration.multipliedBy(3).dividedBy(4));
		return List.of(mid, front, back);
	}

	private Duration clampNonNegative(Duration duration) {
		return duration.isNegative() ? Duration.ZERO : duration;
	}
}

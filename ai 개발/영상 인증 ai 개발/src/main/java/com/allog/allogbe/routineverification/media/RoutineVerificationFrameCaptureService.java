package com.allog.allogbe.routineverification.media;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * VIDEO 제출에서만 사용되는 대표 프레임 캡처 오케스트레이션 (STAGE4).
 * 1차 시도는 영상 중간 지점, 실패 시 앞 지점(1/4) -> 뒤 지점(3/4) 순으로 최대 3회 재시도한다.
 * 실제 디코딩(ffmpeg 등)은 {@link VideoFrameExtractor} 구현체에 위임한다.
 *
 * ⚠️ 이 클래스는 프레임 "추출"만 책임진다 — 추출된 프레임에 루틴 증거가 실제로 담겼는지는
 * STAGE6(Vision)의 몫이라 여기서는 알 수 없다. {@link #captureRepresentativeFrame}은 추출
 * 성공 여부만으로 재시도하므로, 긴 영상에서 중간 지점이 하필 "죽은 시간"이면 그 프레임을 그대로
 * 반환해버린다(실측: skincare_test.mp4, 25초 영상의 중간 지점에 루틴 증거가 없어 Vision이
 * objectPresence=false로 판단한 사례). 영상 길이가 애매한 동안은 {@link #captureAllCandidateFrames}로
 * 후보 프레임을 전부 받아 호출자가 Vision으로 하나씩 넘겨가며 확인하는 편이 안전하다.
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

	/**
	 * mid/front/back 3개 후보 지점에서 프레임 추출을 전부 시도해, 성공한 것들을 시간순 그대로
	 * 반환한다 — {@link #captureRepresentativeFrame}과 달리 첫 성공에서 멈추지 않는다.
	 * 호출자(STAGE6 오케스트레이션)가 각 프레임을 Vision에 순서대로 넣어보고 objectPresence=false면
	 * 다음 프레임으로 넘어가는 용도다. 추출 자체가 3번 다 실패한 경우에만 예외를 던진다.
	 */
	public List<Path> captureAllCandidateFrames(Path videoPath, Duration videoDuration) {
		List<Duration> candidateTimestamps = candidateTimestamps(videoDuration);
		List<Path> frames = new ArrayList<>();
		FrameExtractionAttemptException lastFailure = null;

		for (Duration timestamp : candidateTimestamps) {
			try {
				frames.add(extractor.extractFrame(videoPath, timestamp));
			} catch (FrameExtractionAttemptException e) {
				lastFailure = e;
			}
		}

		if (frames.isEmpty()) {
			throw new FrameCaptureException(
					"영상에서 프레임 추출에 실패했습니다 (%d회 시도): %s".formatted(MAX_ATTEMPTS, videoPath), lastFailure);
		}
		return frames;
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

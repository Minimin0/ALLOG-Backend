package com.allog.allogbe.routineverification.media;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg CLI(PATH상의 ffmpeg 실행 파일 필요)를 서브프로세스로 호출하는 어댑터.
 * ⚠️ 이 클래스는 실제 ffmpeg 바이너리가 설치된 환경에서만 동작한다 — 이번 구현 환경에는
 * ffmpeg가 없어 실행 검증(실측 처리시간 포함)을 하지 못했다. 단위 테스트는
 * {@link RoutineVerificationFrameCaptureService} 가 이 인터페이스의 가짜 구현으로 재시도 로직만 검증한다.
 */
@Component
public class FfmpegVideoFrameExtractor implements VideoFrameExtractor {

	private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

	@Override
	public Path extractFrame(Path videoPath, Duration timestamp) {
		Path outputPath = createTempOutputPath();

		List<String> command = List.of(
				"ffmpeg", "-y",
				"-ss", formatTimestamp(timestamp),
				"-i", videoPath.toString(),
				"-frames:v", "1",
				"-q:v", "2",
				outputPath.toString());

		try {
			Process process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();

			boolean finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new FrameExtractionAttemptException("ffmpeg 프로세스 타임아웃: " + videoPath);
			}
			if (process.exitValue() != 0 || Files.size(outputPath) == 0) {
				throw new FrameExtractionAttemptException(
						"ffmpeg 프레임 추출 실패 (exit=%d): %s".formatted(process.exitValue(), videoPath));
			}
			return outputPath;
		} catch (IOException e) {
			deleteQuietly(outputPath);
			throw new FrameExtractionAttemptException("ffmpeg 실행 실패: " + videoPath, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			deleteQuietly(outputPath);
			throw new FrameExtractionAttemptException("ffmpeg 실행 중단됨: " + videoPath, e);
		} catch (FrameExtractionAttemptException e) {
			deleteQuietly(outputPath);
			throw e;
		}
	}

	private Path createTempOutputPath() {
		try {
			return Files.createTempFile("routine-verification-frame-", ".jpg");
		} catch (IOException e) {
			throw new FrameExtractionAttemptException("임시 출력 파일 생성 실패", e);
		}
	}

	private void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// best-effort cleanup
		}
	}

	private String formatTimestamp(Duration timestamp) {
		long totalSeconds = timestamp.toSeconds();
		long h = totalSeconds / 3600;
		long m = (totalSeconds % 3600) / 60;
		long s = totalSeconds % 60;
		return "%02d:%02d:%02d".formatted(h, m, s);
	}
}

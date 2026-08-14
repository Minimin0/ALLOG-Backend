package com.allog.allogbe.tools.calibration;

import com.allog.allogbe.routineverification.classification.ClassificationDecision;
import com.allog.allogbe.routineverification.classification.RoutineVerificationClassificationRuleEngine;
import com.allog.allogbe.routineverification.domain.QualityCheck;
import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import com.allog.allogbe.routineverification.media.FfmpegVideoFrameExtractor;
import com.allog.allogbe.routineverification.media.ImageQualityAnalyzer;
import com.allog.allogbe.routineverification.media.RoutineVerificationFrameCaptureService;
import com.allog.allogbe.routineverification.vision.ClaudeVisionAnalysisClient;
import com.allog.allogbe.routineverification.vision.RoutineVerificationVisionAnalysisService;
import com.allog.allogbe.routineverification.vision.VisionAnalysisOutcome;
import com.allog.allogbe.routineverification.vision.VisionAnalysisRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * VIDEO 제출 전체 파이프라인(STAGE4 ffmpeg 프레임 추출 -> STAGE0 화질 게이트 -> STAGE6 Vision ->
 * STAGE7 규칙엔진)을 실제 .mp4 파일로 검증하는 일회성 도구. CalibrationRunner와 달리 정적 이미지가
 * 아니라 진짜 영상에서 대표 프레임을 뽑는 STAGE4 컴포넌트(FfmpegVideoFrameExtractor,
 * RoutineVerificationFrameCaptureService)를 그대로 사용한다. 프로덕션 컴포넌트 스캔에는 관여하지
 * 않는다(의도적으로 @Component 미부착).
 *
 * 실행: ANTHROPIC_API_KEY 환경변수 + PATH상의 ffmpeg/ffprobe 필요. ./gradlew runVideoCalibration
 * 선택 인자: VIDEO_CALIBRATION_DIR(기본 ../test video). 파일명은 "{category}_아무거나.mp4" 형식이어야
 * CalibrationRunner.CATEGORY_CONFIGS 의 카테고리로 매핑된다.
 */
public final class VideoCalibrationRunner {

	private VideoCalibrationRunner() {
	}

	public static void main(String[] args) throws Exception {
		System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
		String apiKey = System.getenv("ANTHROPIC_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("ANTHROPIC_API_KEY 환경변수가 필요합니다.");
		}
		String model = System.getenv().getOrDefault("ALLOG_VISION_MODEL", "claude-sonnet-5");
		Path dir = Path.of(System.getenv().getOrDefault("VIDEO_CALIBRATION_DIR", "../test video"));

		ClaudeVisionAnalysisClient client = new ClaudeVisionAnalysisClient(apiKey, model);
		RoutineVerificationVisionAnalysisService visionService = new RoutineVerificationVisionAnalysisService(client);
		ImageQualityAnalyzer qualityAnalyzer = new ImageQualityAnalyzer();
		RoutineVerificationFrameCaptureService frameCaptureService =
				new RoutineVerificationFrameCaptureService(new FfmpegVideoFrameExtractor());
		RoutineVerificationClassificationRuleEngine ruleEngine = new RoutineVerificationClassificationRuleEngine();

		List<Path> videos;
		try (var stream = Files.list(dir)) {
			videos = stream.filter(p -> p.toString().toLowerCase().endsWith(".mp4")).sorted().toList();
		}

		for (Path video : videos) {
			processVideo(video, visionService, qualityAnalyzer, frameCaptureService, ruleEngine);
		}
	}

	private static void processVideo(Path video, RoutineVerificationVisionAnalysisService visionService,
			ImageQualityAnalyzer qualityAnalyzer, RoutineVerificationFrameCaptureService frameCaptureService,
			RoutineVerificationClassificationRuleEngine ruleEngine) throws Exception {
		String filename = video.getFileName().toString();
		String prefix = filename.contains("_") ? filename.substring(0, filename.indexOf('_')) : filename;
		CalibrationRunner.CategoryConfig config = CalibrationRunner.CATEGORY_CONFIGS.get(prefix.toLowerCase());
		System.out.println("=== " + filename + " ===");
		if (config == null) {
			System.out.println("  SKIP: 파일명 접두어로 카테고리를 알 수 없음 (" + prefix + ")");
			return;
		}

		Duration duration = probeDuration(video);
		System.out.println("  videoDuration=" + duration + " category=" + config.category());

		List<Path> candidateFrames;
		try {
			candidateFrames = frameCaptureService.captureAllCandidateFrames(video, duration);
		} catch (RuntimeException e) {
			System.out.println("  [STAGE4] 프레임 추출 실패(3회 재시도 모두 실패): " + e.getMessage());
			return;
		}

		try {
			for (int i = 0; i < candidateFrames.size(); i++) {
				Path framePath = candidateFrames.get(i);
				boolean isLastFrame = i == candidateFrames.size() - 1;
				System.out.println("  --- 후보 프레임 " + (i + 1) + "/" + candidateFrames.size() + " ---");

				FrameAttempt attempt = tryFrame(framePath, config, qualityAnalyzer, visionService, ruleEngine);
				if (attempt == null) {
					continue; // 화질 게이트 탈락 -> 다음 후보 프레임 시도
				}
				if (attempt.retryNextFrame() && !isLastFrame) {
					System.out.println("  (이 프레임엔 루틴 증거가 안 보임(objectPresence=false, 이상징후 없음) "
							+ "-> 다음 후보 프레임 재시도)");
					continue;
				}
				ClassificationDecision decision = attempt.decision();
				System.out.println("  -> 최종 결과: " + decision.aiClassification()
						+ " / " + decision.reviewStatus() + " / " + decision.reviewPriority());
				return;
			}
			System.out.println("  -> 최종 결과: 모든 후보 프레임이 화질 게이트를 통과하지 못함 (LowQualityMediaException)");
		} finally {
			for (Path framePath : candidateFrames) {
				Files.deleteIfExists(framePath);
			}
			System.out.println();
		}
	}

	private record FrameAttempt(ClassificationDecision decision, boolean retryNextFrame) {
	}

	/**
	 * 프레임 하나를 STAGE0(화질) -> STAGE6(Vision) -> STAGE7(규칙엔진)까지 통과시킨다. 화질 게이트
	 * 탈락 시 null. retryNextFrame은 "objectPresence=false + 이상징후 없음"일 때만 true다 —
	 * ⚠️ 이상징후(anomalyFlags)가 있는 REJECT_CANDIDATE는 절대 다음 프레임으로 재시도하지 않는다.
	 * 화면재촬영/워터마크 같은 진짜 조작 증거를 한 프레임에서 찾았는데 다른 프레임이 깨끗하다고
	 * 다시 시도해버리면, 조작된 영상도 "운 좋은 프레임"만 걸리면 통과하는 우회로가 생긴다.
	 */
	private static FrameAttempt tryFrame(Path framePath, CalibrationRunner.CategoryConfig config,
			ImageQualityAnalyzer qualityAnalyzer, RoutineVerificationVisionAnalysisService visionService,
			RoutineVerificationClassificationRuleEngine ruleEngine) throws Exception {
		BufferedImage frame = ImageIO.read(framePath.toFile());
		QualityCheck quality = qualityAnalyzer.analyze(frame);
		System.out.println("  [STAGE0 화질게이트] blurScore=" + quality.getBlurScore()
				+ " isBlurry=" + quality.isBlurry()
				+ " resolution=" + quality.getResolutionWidth() + "x" + quality.getResolutionHeight()
				+ " passesMinResolution=" + quality.isPassesMinResolution());

		if (!quality.isPassesMinResolution() || quality.isBlurry()) {
			System.out.println("  [STAGE0] 화질 게이트 탈락 (AI 호출 안 됨)");
			return null;
		}

		byte[] bytes = Files.readAllBytes(framePath);
		VisionAnalysisRequest request = new VisionAnalysisRequest(
				bytes, "image/jpeg", config.category(), config.description(), config.expectedObjects());
		VisionAnalysisOutcome outcome = visionService.analyze(request);

		boolean retryNextFrame = false;
		if (outcome.available()) {
			VisionAnalysisResult result = outcome.result();
			System.out.println("  [STAGE6 Vision] objectPresence=" + result.getObjectPresence()
					+ " relevance=" + result.getRelevanceScore()
					+ " anomalies=" + result.getAnomalyFlags()
					+ " isFramedProperly=" + result.getFramedProperly());
			System.out.println("  summary: " + result.getSummary());

			boolean objectMissing = !Boolean.TRUE.equals(result.getObjectPresence());
			boolean hasAnomalies = result.getAnomalyFlags() != null && !result.getAnomalyFlags().isEmpty();
			retryNextFrame = objectMissing && !hasAnomalies;
		} else {
			System.out.println("  [STAGE6 Vision] 3회 재시도 후에도 실패 (available=false)");
		}

		ClassificationDecision decision = ruleEngine.classify(false, outcome);
		return new FrameAttempt(decision, retryNextFrame);
	}

	private static Duration probeDuration(Path video) throws Exception {
		List<String> command = List.of("ffprobe", "-v", "error", "-show_entries", "format=duration",
				"-of", "default=noprint_wrappers=1:nokey=1", video.toString());
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output;
		try (var in = process.getInputStream()) {
			output = new String(in.readAllBytes()).trim();
		}
		process.waitFor();
		return Duration.ofMillis((long) (Double.parseDouble(output) * 1000));
	}
}

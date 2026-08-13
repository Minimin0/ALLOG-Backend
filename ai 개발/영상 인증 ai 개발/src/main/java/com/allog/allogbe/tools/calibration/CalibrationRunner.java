package com.allog.allogbe.tools.calibration;

import com.allog.allogbe.routineverification.domain.QualityCheck;
import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import com.allog.allogbe.routineverification.duplicate.PerceptualHash;
import com.allog.allogbe.routineverification.duplicate.PerceptualHashCalculator;
import com.allog.allogbe.routineverification.media.ImageQualityAnalyzer;
import com.allog.allogbe.routineverification.vision.ChallengeCategory;
import com.allog.allogbe.routineverification.vision.ClaudeVisionAnalysisClient;
import com.allog.allogbe.routineverification.vision.RoutineVerificationVisionAnalysisService;
import com.allog.allogbe.routineverification.vision.VisionAnalysisOutcome;
import com.allog.allogbe.routineverification.vision.VisionAnalysisRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * STAGE7 임계치 캘리브레이션 전용 일회성 도구. 프로덕션 앱(Spring 컴포넌트 스캔)에는 관여하지 않는다
 * (의도적으로 @Component 미부착 — 절대 서비스 빈으로 등록되지 않음).
 * calibration-images/ 의 라벨링된 이미지를 실제 STAGE6 파이프라인(ClaudeVisionAnalysisClient +
 * RoutineVerificationVisionAnalysisService)에 통과시켜 결과를 TSV로 남긴다. mock 없음, 실제 API 호출.
 *
 * 실행: ANTHROPIC_API_KEY 환경변수 필요. ./gradlew runCalibration
 * 선택 인자: CALIBRATION_IMAGES_DIR(기본 ../calibration-images), CALIBRATION_CATEGORIES(콤마구분, 기본 전체),
 *          CALIBRATION_OUTPUT(기본 build/calibration-results.tsv)
 */
public final class CalibrationRunner {

	private record CategoryConfig(ChallengeCategory category, String description, List<String> expectedObjects) {
	}

	private static final Map<String, CategoryConfig> CATEGORY_CONFIGS = new LinkedHashMap<>();

	static {
		CATEGORY_CONFIGS.put("skincare", new CategoryConfig(ChallengeCategory.SKINCARE,
				"스킨케어 루틴(세안·토너·크림 등) 수행 인증", List.of("스킨케어 제품", "거울에 비친 얼굴 또는 얼굴")));
		CATEGORY_CONFIGS.put("meal", new CategoryConfig(ChallengeCategory.MEAL,
				"건강한 식사 챙겨먹기 루틴 인증", List.of("식사가 차려진 접시 또는 그릇", "식탁")));
		CATEGORY_CONFIGS.put("exercise", new CategoryConfig(ChallengeCategory.EXERCISE,
				"운동 루틴 수행 인증", List.of("운동 기구 또는 운동복", "운동하는 모습")));
		CATEGORY_CONFIGS.put("sleep", new CategoryConfig(ChallengeCategory.SLEEP,
				"수면 시간 기록 루틴 인증", List.of("수면 시간이 표시된 화면(알람/수면 트래커 앱) 또는 침대")));
	}

	private CalibrationRunner() {
	}

	public static void main(String[] args) throws Exception {
		String apiKey = System.getenv("ANTHROPIC_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("ANTHROPIC_API_KEY 환경변수가 필요합니다.");
		}
		String model = System.getenv().getOrDefault("ALLOG_VISION_MODEL", "claude-sonnet-5");
		Path root = Path.of(System.getenv().getOrDefault("CALIBRATION_IMAGES_DIR", "../calibration-images"));
		Path output = Path.of(System.getenv().getOrDefault("CALIBRATION_OUTPUT", "build/calibration-results.tsv"));
		String categoryFilter = System.getenv("CALIBRATION_CATEGORIES");
		List<String> categories = categoryFilter == null || categoryFilter.isBlank()
				? List.copyOf(CATEGORY_CONFIGS.keySet())
				: List.of(categoryFilter.split(","));

		ClaudeVisionAnalysisClient client = new ClaudeVisionAnalysisClient(apiKey, model);
		RoutineVerificationVisionAnalysisService service = new RoutineVerificationVisionAnalysisService(client);
		PerceptualHashCalculator hashCalculator = new PerceptualHashCalculator();
		ImageQualityAnalyzer qualityAnalyzer = new ImageQualityAnalyzer();

		List<String[]> rows = new ArrayList<>();
		rows.add(new String[]{"category", "filename", "label", "subtype",
				"blurScore", "isBlurry", "resolution", "passesMinResolution",
				"objectPresence", "detectedObjects", "relevanceScore", "anomalyFlags", "confidence", "summary",
				"isFramedProperly", "framingIssue", "available"});

		for (String categoryKey : categories) {
			CategoryConfig config = CATEGORY_CONFIGS.get(categoryKey.trim());
			if (config == null) {
				System.out.println("SKIP unknown category: " + categoryKey);
				continue;
			}
			Path dir = root.resolve(categoryKey.trim());
			if (!Files.isDirectory(dir)) {
				System.out.println("SKIP missing dir: " + dir);
				continue;
			}
			List<Path> files;
			try (var stream = Files.list(dir)) {
				files = stream.filter(Files::isRegularFile).sorted().toList();
			}
			for (Path file : files) {
				processImage(service, qualityAnalyzer, config, categoryKey.trim(), file, rows);
			}
		}

		Path dupDir = root.resolve("duplicates");
		if (Files.isDirectory(dupDir)) {
			List<Path> dupFiles;
			try (var stream = Files.list(dupDir)) {
				dupFiles = stream.filter(Files::isRegularFile).sorted().toList();
			}
			if (dupFiles.size() >= 2) {
				BufferedImage img1 = ImageIO.read(dupFiles.get(0).toFile());
				BufferedImage img2 = ImageIO.read(dupFiles.get(1).toFile());
				PerceptualHash h1 = hashCalculator.calculate(img1);
				PerceptualHash h2 = hashCalculator.calculate(img2);
				int distance = h1.hammingDistance(h2);
				String pairName = dupFiles.get(0).getFileName() + " vs " + dupFiles.get(1).getFileName();
				System.out.println("DUPLICATE PAIR: " + pairName + " hammingDistance=" + distance);
				rows.add(new String[]{"duplicates", pairName, "pair", "hamming=" + distance,
						"", "", "", "", "", "", ""});
			}
		}

		Files.createDirectories(output.getParent());
		try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
			for (String[] row : rows) {
				writer.write(String.join("\t", escapeAll(row)));
				writer.newLine();
			}
		}
		System.out.println("Done. Results written to " + output.toAbsolutePath());
	}

	private static void processImage(RoutineVerificationVisionAnalysisService service,
			ImageQualityAnalyzer qualityAnalyzer, CategoryConfig config,
			String categoryKey, Path file, List<String[]> rows) throws Exception {
		String filename = file.getFileName().toString();
		String[] labelInfo = parseLabel(filename);
		byte[] bytes = Files.readAllBytes(file);
		String mediaType = mediaTypeOf(filename);

		BufferedImage image = ImageIO.read(file.toFile());
		QualityCheck quality = qualityAnalyzer.analyze(image);
		String blurScoreStr = String.valueOf(quality.getBlurScore());
		String isBlurryStr = String.valueOf(quality.isBlurry());
		String resolutionStr = quality.getResolutionWidth() + "x" + quality.getResolutionHeight();
		String passesMinResolutionStr = String.valueOf(quality.isPassesMinResolution());
		System.out.println("  [quality] blurScore=" + blurScoreStr + " isBlurry=" + isBlurryStr
				+ " resolution=" + resolutionStr + " passesMinResolution=" + passesMinResolutionStr);

		VisionAnalysisRequest request = new VisionAnalysisRequest(
				bytes, mediaType, config.category(), config.description(), config.expectedObjects());

		System.out.println("Calling Vision API: " + categoryKey + "/" + filename + " (" + bytes.length + " bytes) ...");
		VisionAnalysisOutcome outcome;
		try {
			outcome = service.analyze(request);
		} catch (RuntimeException e) {
			System.out.println("  ERROR: " + e.getMessage());
			rows.add(new String[]{categoryKey, filename, labelInfo[0], labelInfo[1],
					blurScoreStr, isBlurryStr, resolutionStr, passesMinResolutionStr,
					"", "", "", "", "", "ERROR: " + e.getMessage(), "", "", "false"});
			return;
		}
		if (!outcome.available()) {
			System.out.println("  UNAVAILABLE (3회 재시도 실패)");
			rows.add(new String[]{categoryKey, filename, labelInfo[0], labelInfo[1],
					blurScoreStr, isBlurryStr, resolutionStr, passesMinResolutionStr,
					"", "", "", "", "", "UNAVAILABLE", "", "", "false"});
			return;
		}
		VisionAnalysisResult r = outcome.result();
		rows.add(new String[]{
				categoryKey, filename, labelInfo[0], labelInfo[1],
				blurScoreStr, isBlurryStr, resolutionStr, passesMinResolutionStr,
				String.valueOf(r.getObjectPresence()),
				String.join(";", r.getDetectedObjects() == null ? List.of() : r.getDetectedObjects()),
				String.valueOf(r.getRelevanceScore()),
				String.join(";", r.getAnomalyFlags() == null ? List.of() : r.getAnomalyFlags()),
				String.valueOf(r.getConfidence()),
				r.getSummary() == null ? "" : r.getSummary(),
				String.valueOf(r.getFramedProperly()),
				r.getFramingIssue() == null ? "" : r.getFramingIssue(),
				"true"
		});
		System.out.println("  -> objectPresence=" + r.getObjectPresence() + " relevance=" + r.getRelevanceScore()
				+ " anomalies=" + r.getAnomalyFlags() + " confidence=" + r.getConfidence()
				+ " isFramedProperly=" + r.getFramedProperly());
	}

	private static String[] parseLabel(String filename) {
		String base = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
		if (base.startsWith("pass")) {
			return new String[]{"pass", ""};
		}
		if (base.startsWith("review")) {
			return new String[]{"review", ""};
		}
		if (base.startsWith("reject_anomaly")) {
			return new String[]{"reject", "anomaly"};
		}
		if (base.startsWith("reject_unrelated")) {
			return new String[]{"reject", "unrelated"};
		}
		if (base.startsWith("reject")) {
			return new String[]{"reject", ""};
		}
		return new String[]{"unknown", ""};
	}

	private static String mediaTypeOf(String filename) {
		String lower = filename.toLowerCase();
		if (lower.endsWith(".png")) {
			return "image/png";
		}
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (lower.endsWith(".webp")) {
			return "image/webp";
		}
		return "application/octet-stream";
	}

	private static String[] escapeAll(String[] row) {
		String[] out = new String[row.length];
		for (int i = 0; i < row.length; i++) {
			out[i] = row[i] == null ? "" : row[i].replace("\t", " ").replace("\n", " ").replace("\r", " ");
		}
		return out;
	}
}

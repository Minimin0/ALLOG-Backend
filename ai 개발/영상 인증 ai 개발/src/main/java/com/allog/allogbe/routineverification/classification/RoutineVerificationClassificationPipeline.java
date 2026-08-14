package com.allog.allogbe.routineverification.classification;

import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.domain.QualityCheck;
import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import com.allog.allogbe.routineverification.duplicate.DuplicateCheckResult;
import com.allog.allogbe.routineverification.duplicate.PerceptualHash;
import com.allog.allogbe.routineverification.duplicate.PerceptualHashCalculator;
import com.allog.allogbe.routineverification.duplicate.RoutineVerificationDuplicateDetector;
import com.allog.allogbe.routineverification.exception.LowQualityMediaException;
import com.allog.allogbe.routineverification.media.ImageQualityAnalyzer;
import com.allog.allogbe.routineverification.service.RoutineVerificationSubmissionGate;
import com.allog.allogbe.routineverification.vision.RoutineVerificationVisionAnalysisService;
import com.allog.allogbe.routineverification.vision.VisionAnalysisOutcome;
import com.allog.allogbe.routineverification.vision.VisionAnalysisRequest;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * STAGE3(게이트) -> 영상 품질 확인(선명도/해상도, AI 호출 없음) -> STAGE5(중복) -> STAGE6(Vision)
 * -> STAGE7(규칙엔진) 순서로 실행하는 오케스트레이션.
 * STAGE3 게이트 또는 화질 게이트 실패 시 즉시 예외가 전파되어 이후 단계(중복/Vision 호출)는 전혀
 * 실행되지 않는다 — 화질 게이트는 STAGE3 시간 게이트와 동일한 "조기 종료" 패턴이다.
 * 중복으로 판정되면 Vision API 호출 자체를 생략한다(비용 절감 + 이미 REJECT_CANDIDATE 로 귀결되므로 불필요).
 */
@Component
public class RoutineVerificationClassificationPipeline {

	private final RoutineVerificationSubmissionGate gate;
	private final ImageQualityAnalyzer qualityAnalyzer;
	private final PerceptualHashCalculator hashCalculator;
	private final RoutineVerificationDuplicateDetector duplicateDetector;
	private final RoutineVerificationVisionAnalysisService visionAnalysisService;
	private final RoutineVerificationClassificationRuleEngine ruleEngine;

	public RoutineVerificationClassificationPipeline(
			RoutineVerificationSubmissionGate gate,
			ImageQualityAnalyzer qualityAnalyzer,
			PerceptualHashCalculator hashCalculator,
			RoutineVerificationDuplicateDetector duplicateDetector,
			RoutineVerificationVisionAnalysisService visionAnalysisService,
			RoutineVerificationClassificationRuleEngine ruleEngine) {
		this.gate = gate;
		this.qualityAnalyzer = qualityAnalyzer;
		this.hashCalculator = hashCalculator;
		this.duplicateDetector = duplicateDetector;
		this.visionAnalysisService = visionAnalysisService;
		this.ruleEngine = ruleEngine;
	}

	public RoutineVerificationClassificationOutput process(RoutineVerificationClassificationInput input) {
		MetadataCheck gateResult = gate.validate(input.submitRequest());

		// 규칙 0: 화질 게이트. 실패 시 규칙 1~5(중복/Vision/규칙엔진)는 전혀 실행되지 않는다.
		QualityCheck qualityCheck = qualityAnalyzer.analyze(input.image());
		if (!qualityCheck.isPassesMinResolution()) {
			throw new LowQualityMediaException("LOW_RESOLUTION",
					"해상도가 너무 낮습니다(%dx%d). 더 높은 해상도로 다시 촬영해서 제출해주세요."
							.formatted(qualityCheck.getResolutionWidth(), qualityCheck.getResolutionHeight()));
		}
		if (qualityCheck.isBlurry()) {
			throw new LowQualityMediaException("LOW_QUALITY_BLUR",
					"이미지가 흐릿합니다(blurScore=%.1f). 초점을 맞춰 다시 촬영해서 제출해주세요."
							.formatted(qualityCheck.getBlurScore()));
		}

		PerceptualHash hash = hashCalculator.calculate(input.image());
		DuplicateCheckResult duplicateResult = duplicateDetector.detect(
				input.submitRequest().userId(),
				input.submitRequest().challengeId(),
				hash,
				input.submitRequest().submittedAt());

		VisionAnalysisOutcome visionOutcome = duplicateResult.duplicate()
				? VisionAnalysisOutcome.unavailable() // 중복 확정 -> 규칙엔진이 참조하지 않으므로 실제 호출은 생략됨
				: visionAnalysisService.analyze(new VisionAnalysisRequest(
						toBytes(input.image()), input.imageMediaType(),
						input.category(), input.routineDescription(), input.expectedObjects()));

		ClassificationDecision decision = ruleEngine.classify(duplicateResult.duplicate(), visionOutcome);

		MetadataCheck finalMetadataCheck = new MetadataCheck(
				gateResult.isWithinTimeWindow(), duplicateResult.duplicate(), duplicateResult.duplicateOfId());

		VisionAnalysisResult visionResult = (!duplicateResult.duplicate() && visionOutcome.available())
				? visionOutcome.result()
				: null;

		QualityCheck finalQualityCheck = visionResult != null
				? qualityCheck.withFraming(visionResult.getFramedProperly(), visionResult.getFramingIssue())
				: qualityCheck;

		return new RoutineVerificationClassificationOutput(finalMetadataCheck, visionResult, finalQualityCheck, decision);
	}

	private byte[] toBytes(BufferedImage image) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "jpg", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException("이미지 인코딩 실패", e);
		}
	}
}

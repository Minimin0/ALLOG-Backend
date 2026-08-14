package com.allog.allogbe.routineverification.media;

import com.allog.allogbe.routineverification.domain.QualityCheck;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

/**
 * 영상 품질 확인 — 선명도/해상도 게이트 (STAGE4 직후, 결정론적 알고리즘, AI 호출 없음).
 * 그레이스케일 변환 -> 3x3 라플라시안 컨볼루션 -> 응답값의 분산(blurScore). 값이 낮을수록 에지가
 * 적어(흐릿해) 보인다는 뜻이다. isFramedProperly/framingIssue 는 여기서 판단하지 않는다 — Vision
 * AI(STAGE6)의 몫이며, 이 클래스는 "필터"이지 최종 "판정자"가 아니다.
 */
@Component
public class ImageQualityAnalyzer {

	/**
	 * 캘리브레이션 45장(라벨된 실제 카메라 사진, docs/calibration/ 참고) 기준 재조정.
	 * 기존 100은 사람이 정상(pass)으로 라벨한 사진의 20%(20장 중 4장, 최저 27.58)를 오탐으로
	 * 하드 리젝시켰다. 20은 그 20장 전체(최저 27.58)를 통과시키면서, 실제로 흔들리거나
	 * 초점이 안 맞은 reject 샘플 4장(blurScore 1.4~2.84)은 전부 정확히 차단한다 —
	 * 두 그룹 사이에 10배 가까운 여유가 있어 이 값으로 명확히 갈린다.
	 */
	static final float BLUR_THRESHOLD = 20f;
	static final int MIN_RESOLUTION_WIDTH = 480;
	static final int MIN_RESOLUTION_HEIGHT = 480;

	public QualityCheck analyze(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		boolean passesMinResolution = width >= MIN_RESOLUTION_WIDTH && height >= MIN_RESOLUTION_HEIGHT;

		float blurScore = (float) laplacianVariance(image);
		boolean blurry = blurScore < BLUR_THRESHOLD;

		return new QualityCheck(blurScore, blurry, width, height, passesMinResolution, null, null);
	}

	private double laplacianVariance(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		double[][] gray = toGrayscale(image);

		double sum = 0;
		double sumSquares = 0;
		long count = 0;
		for (int y = 1; y < height - 1; y++) {
			for (int x = 1; x < width - 1; x++) {
				double laplacian = -4 * gray[y][x]
						+ gray[y - 1][x] + gray[y + 1][x] + gray[y][x - 1] + gray[y][x + 1];
				sum += laplacian;
				sumSquares += laplacian * laplacian;
				count++;
			}
		}
		if (count == 0) {
			return 0;
		}
		double mean = sum / count;
		return (sumSquares / count) - (mean * mean);
	}

	private double[][] toGrayscale(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		double[][] gray = new double[height][width];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int rgb = image.getRGB(x, y);
				int r = (rgb >> 16) & 0xFF;
				int g = (rgb >> 8) & 0xFF;
				int b = rgb & 0xFF;
				gray[y][x] = 0.299 * r + 0.587 * g + 0.114 * b;
			}
		}
		return gray;
	}
}

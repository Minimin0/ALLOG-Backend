package com.allog.allogbe.routineverification.media;

import com.allog.allogbe.routineverification.domain.QualityCheck;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class ImageQualityAnalyzerTest {

	private final ImageQualityAnalyzer analyzer = new ImageQualityAnalyzer();

	@Test
	void 고대비_체커보드_이미지는_흐리지_않다고_판단한다() {
		BufferedImage sharp = checkerboardImage(500, 500, 10);

		QualityCheck result = analyzer.analyze(sharp);

		assertThat(result.isBlurry()).isFalse();
		assertThat(result.getBlurScore()).isGreaterThan(ImageQualityAnalyzer.BLUR_THRESHOLD);
	}

	@Test
	void 단색에_가까운_매끈한_이미지는_흐리다고_판단한다() {
		BufferedImage smooth = smoothGradientImage(500, 500);

		QualityCheck result = analyzer.analyze(smooth);

		assertThat(result.isBlurry()).isTrue();
		assertThat(result.getBlurScore()).isLessThan(ImageQualityAnalyzer.BLUR_THRESHOLD);
	}

	@Test
	void 최소_해상도_미만이면_통과하지_못한다() {
		BufferedImage tiny = checkerboardImage(100, 100, 10);

		QualityCheck result = analyzer.analyze(tiny);

		assertThat(result.isPassesMinResolution()).isFalse();
		assertThat(result.getResolutionWidth()).isEqualTo(100);
		assertThat(result.getResolutionHeight()).isEqualTo(100);
	}

	@Test
	void 최소_해상도_이상이면_통과한다() {
		BufferedImage large = checkerboardImage(
				ImageQualityAnalyzer.MIN_RESOLUTION_WIDTH, ImageQualityAnalyzer.MIN_RESOLUTION_HEIGHT, 10);

		QualityCheck result = analyzer.analyze(large);

		assertThat(result.isPassesMinResolution()).isTrue();
	}

	@Test
	void 구도_판단_필드는_이_단계에서는_비어있다() {
		BufferedImage sharp = checkerboardImage(500, 500, 10);

		QualityCheck result = analyzer.analyze(sharp);

		assertThat(result.isFramedProperly()).isNull();
		assertThat(result.getFramingIssue()).isNull();
	}

	private BufferedImage checkerboardImage(int width, int height, int blockSize) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean black = ((x / blockSize) + (y / blockSize)) % 2 == 0;
				int gray = black ? 10 : 240;
				image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
			}
		}
		return image;
	}

	private BufferedImage smoothGradientImage(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int gray = 128 + (int) (2 * Math.sin(x / 400.0) + 2 * Math.cos(y / 400.0));
				image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
			}
		}
		return image;
	}
}

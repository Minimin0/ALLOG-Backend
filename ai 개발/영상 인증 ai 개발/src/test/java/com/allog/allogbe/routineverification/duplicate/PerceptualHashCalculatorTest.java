package com.allog.allogbe.routineverification.duplicate;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PerceptualHashCalculatorTest {

	private static final int THRESHOLD = RoutineVerificationDuplicateDetector.HAMMING_DISTANCE_THRESHOLD;

	private final PerceptualHashCalculator calculator = new PerceptualHashCalculator();

	@Test
	void 동일_이미지는_해밍_거리가_0이다() {
		BufferedImage image = patternImage(1L);
		BufferedImage sameImageCopy = cloneImage(image);

		PerceptualHash hashA = calculator.calculate(image);
		PerceptualHash hashB = calculator.calculate(sameImageCopy);

		assertThat(hashA.hammingDistance(hashB)).isZero();
	}

	@Test
	void 픽셀_일부만_다른_유사_이미지는_임계치_이하의_거리를_가진다() {
		BufferedImage original = patternImage(1L);
		BufferedImage slightlyModified = withNoise(original, 8);

		PerceptualHash hashA = calculator.calculate(original);
		PerceptualHash hashB = calculator.calculate(slightlyModified);

		assertThat(hashA.hammingDistance(hashB)).isLessThanOrEqualTo(THRESHOLD);
	}

	@Test
	void 완전히_무관한_이미지는_임계치를_초과하는_거리를_가진다() {
		BufferedImage smoothGradient = horizontalGradientImage();
		BufferedImage fineCheckerboard = checkerboardImage();

		PerceptualHash hashA = calculator.calculate(smoothGradient);
		PerceptualHash hashB = calculator.calculate(fineCheckerboard);

		assertThat(hashA.hammingDistance(hashB)).isGreaterThan(THRESHOLD);
	}

	private BufferedImage patternImage(long seed) {
		int size = 256;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		Random random = new Random(seed);
		var graphics = image.createGraphics();
		graphics.setColor(java.awt.Color.WHITE);
		graphics.fillRect(0, 0, size, size);
		for (int i = 0; i < 40; i++) {
			int gray = random.nextInt(256);
			graphics.setColor(new java.awt.Color(gray, gray, gray));
			int w = 10 + random.nextInt(60);
			int h = 10 + random.nextInt(60);
			int x = random.nextInt(size - w);
			int y = random.nextInt(size - h);
			graphics.fillRect(x, y, w, h);
		}
		graphics.dispose();
		return image;
	}

	private BufferedImage cloneImage(BufferedImage source) {
		BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), source.getType());
		var graphics = copy.createGraphics();
		graphics.drawImage(source, 0, 0, null);
		graphics.dispose();
		return copy;
	}

	private BufferedImage withNoise(BufferedImage source, int pixelsToFlip) {
		BufferedImage copy = cloneImage(source);
		Random random = new Random(99L);
		for (int i = 0; i < pixelsToFlip; i++) {
			int x = random.nextInt(copy.getWidth());
			int y = random.nextInt(copy.getHeight());
			copy.setRGB(x, y, 0x000000);
		}
		return copy;
	}

	private BufferedImage horizontalGradientImage() {
		int size = 256;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int gray = (int) (255.0 * x / size);
				image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
			}
		}
		return image;
	}

	private BufferedImage checkerboardImage() {
		int size = 256;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				boolean black = ((x / 2) + (y / 2)) % 2 == 0;
				int gray = black ? 0 : 255;
				image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
			}
		}
		return image;
	}
}

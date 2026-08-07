package com.allog.allogbe.routineverification.duplicate;

import org.springframework.stereotype.Component;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * DCT 기반 Perceptual Hash(pHash) 계산기.
 * 32x32 그레이스케일로 축소 -> 2D DCT -> 저주파 8x8(DC 제외 평균) -> 64bit 해시.
 */
@Component
public class PerceptualHashCalculator {

	private static final int SAMPLE_SIZE = 32;
	private static final int HASH_DIMENSION = 8;

	public PerceptualHash calculate(BufferedImage source) {
		double[][] grayscale = toGrayscale(resize(source, SAMPLE_SIZE, SAMPLE_SIZE));
		double[][] dct = dct2D(grayscale);
		return buildHash(dct);
	}

	private BufferedImage resize(BufferedImage source, int width, int height) {
		BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = resized.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(source, 0, 0, width, height, null);
		g.dispose();
		return resized;
	}

	private double[][] toGrayscale(BufferedImage image) {
		int size = image.getWidth();
		double[][] gray = new double[size][size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int rgb = image.getRGB(x, y);
				int r = (rgb >> 16) & 0xFF;
				int g = (rgb >> 8) & 0xFF;
				int b = rgb & 0xFF;
				gray[y][x] = 0.299 * r + 0.587 * g + 0.114 * b;
			}
		}
		return gray;
	}

	private double[][] dct2D(double[][] matrix) {
		int n = matrix.length;
		double[][] afterRows = new double[n][n];
		for (int y = 0; y < n; y++) {
			afterRows[y] = dct1D(matrix[y]);
		}

		double[][] result = new double[n][n];
		double[] column = new double[n];
		for (int x = 0; x < n; x++) {
			for (int y = 0; y < n; y++) {
				column[y] = afterRows[y][x];
			}
			double[] transformedColumn = dct1D(column);
			for (int y = 0; y < n; y++) {
				result[y][x] = transformedColumn[y];
			}
		}
		return result;
	}

	private double[] dct1D(double[] input) {
		int n = input.length;
		double[] output = new double[n];
		for (int u = 0; u < n; u++) {
			double sum = 0;
			for (int x = 0; x < n; x++) {
				sum += input[x] * Math.cos((Math.PI / n) * (x + 0.5) * u);
			}
			double c = (u == 0) ? Math.sqrt(1.0 / n) : Math.sqrt(2.0 / n);
			output[u] = c * sum;
		}
		return output;
	}

	/** 좌상단 8x8 저주파 계수 중 DC(0,0)를 제외한 평균보다 큰 계수는 1, 아니면 0. */
	private PerceptualHash buildHash(double[][] dct) {
		double[] lowFrequencies = new double[HASH_DIMENSION * HASH_DIMENSION];
		int idx = 0;
		double sum = 0;
		for (int y = 0; y < HASH_DIMENSION; y++) {
			for (int x = 0; x < HASH_DIMENSION; x++) {
				double value = dct[y][x];
				lowFrequencies[idx++] = value;
				if (!(x == 0 && y == 0)) {
					sum += value;
				}
			}
		}
		double average = sum / (lowFrequencies.length - 1);

		long bits = 0L;
		for (double value : lowFrequencies) {
			bits <<= 1;
			if (value > average) {
				bits |= 1L;
			}
		}
		return new PerceptualHash(bits);
	}
}

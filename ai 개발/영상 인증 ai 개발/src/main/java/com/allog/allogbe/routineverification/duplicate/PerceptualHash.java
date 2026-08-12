package com.allog.allogbe.routineverification.duplicate;

/** 64비트 pHash. 두 해시의 유사도는 해밍 거리(다른 비트 수, 0~64)로 판단한다. */
public record PerceptualHash(long bits) {

	public int hammingDistance(PerceptualHash other) {
		return Long.bitCount(this.bits ^ other.bits);
	}
}

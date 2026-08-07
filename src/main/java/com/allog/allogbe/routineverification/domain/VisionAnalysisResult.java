package com.allog.allogbe.routineverification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;

import java.util.List;

/**
 * Vision AI(STAGE6)의 "제안" 결과. 최종 인증 확정 권한이 없으며,
 * reviewStatus 를 AUTO_VALID/FLAGGED_FOR_REVIEW 로 전환하는 근거로만 쓰인다.
 * STAGE3~5 시점에는 전체 필드가 null 이다.
 */
@Embeddable
public class VisionAnalysisResult {

	private Boolean objectPresence;

	@Convert(converter = StringListJsonConverter.class)
	@Column(name = "detected_objects", columnDefinition = "TEXT")
	private List<String> detectedObjects;

	private Double relevanceScore;

	@Convert(converter = StringListJsonConverter.class)
	@Column(name = "anomaly_flags", columnDefinition = "TEXT")
	private List<String> anomalyFlags;

	private Double confidence;

	@Column(columnDefinition = "TEXT")
	private String summary;

	protected VisionAnalysisResult() {
	}

	public VisionAnalysisResult(Boolean objectPresence, List<String> detectedObjects, Double relevanceScore,
			List<String> anomalyFlags, Double confidence, String summary) {
		this.objectPresence = objectPresence;
		this.detectedObjects = detectedObjects;
		this.relevanceScore = relevanceScore;
		this.anomalyFlags = anomalyFlags;
		this.confidence = confidence;
		this.summary = summary;
	}

	public Boolean getObjectPresence() {
		return objectPresence;
	}

	public List<String> getDetectedObjects() {
		return detectedObjects;
	}

	public Double getRelevanceScore() {
		return relevanceScore;
	}

	public List<String> getAnomalyFlags() {
		return anomalyFlags;
	}

	public Double getConfidence() {
		return confidence;
	}

	public String getSummary() {
		return summary;
	}
}

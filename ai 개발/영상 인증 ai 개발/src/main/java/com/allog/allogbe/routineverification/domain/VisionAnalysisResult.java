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

	/**
	 * 영상 품질 확인 중 구도(프레이밍) 판단. 선명도는 STAGE4 알고리즘 게이트가 이미 처리하므로 여기서
	 * 다시 묻지 않는다. QualityCheck 도 같은 테이블에 임베드되므로 컬럼명이 겹치지 않도록 접두어를 둔다.
	 */
	@Column(name = "vision_framed_properly")
	private Boolean framedProperly;

	@Column(name = "vision_framing_issue", columnDefinition = "TEXT")
	private String framingIssue;

	protected VisionAnalysisResult() {
	}

	/** 프레이밍 판단이 없던 기존 호출부와의 호환을 위한 생성자. framedProperly/framingIssue 는 null 이 된다. */
	public VisionAnalysisResult(Boolean objectPresence, List<String> detectedObjects, Double relevanceScore,
			List<String> anomalyFlags, Double confidence, String summary) {
		this(objectPresence, detectedObjects, relevanceScore, anomalyFlags, confidence, summary, null, null);
	}

	public VisionAnalysisResult(Boolean objectPresence, List<String> detectedObjects, Double relevanceScore,
			List<String> anomalyFlags, Double confidence, String summary,
			Boolean framedProperly, String framingIssue) {
		this.objectPresence = objectPresence;
		this.detectedObjects = detectedObjects;
		this.relevanceScore = relevanceScore;
		this.anomalyFlags = anomalyFlags;
		this.confidence = confidence;
		this.summary = summary;
		this.framedProperly = framedProperly;
		this.framingIssue = framingIssue;
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

	public Boolean getFramedProperly() {
		return framedProperly;
	}

	public String getFramingIssue() {
		return framingIssue;
	}
}

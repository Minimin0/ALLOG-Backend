package com.allog.allogbe.routineverification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 영상 품질 확인(STAGE4 확장). 선명도/해상도는 결정론적 알고리즘 계산 결과이며 그 자체로
 * "게이트"(즉시 반려)로 쓰인다. isFramedProperly/framingIssue 는 Vision AI(STAGE6)의 판단이며
 * "필터"일 뿐 판정자가 아니다 — STAGE7 분류 로직의 REVIEW_REQUIRED 신호 중 하나로만 편입된다.
 * 화질 게이트를 통과하기 전(STAGE4 직후)에는 isFramedProperly/framingIssue 가 null 이다.
 */
@Embeddable
public class QualityCheck {

	@Column(name = "blur_score")
	private Float blurScore;

	// 개별 필드가 아니라 QualityCheck 전체가 null일 수 있으므로(초기 PENDING 저장,
	// VIDEO/APP_RECORD 폴백 등 화질 게이트를 아직/영영 거치지 않은 경우) nullable=false 를 걸지 않는다.
	@Column(name = "is_blurry")
	private boolean blurry;

	@Column(name = "resolution_width")
	private Integer resolutionWidth;

	@Column(name = "resolution_height")
	private Integer resolutionHeight;

	@Column(name = "passes_min_resolution")
	private boolean passesMinResolution;

	@Column(name = "is_framed_properly")
	private Boolean framedProperly;

	@Column(name = "framing_issue", columnDefinition = "TEXT")
	private String framingIssue;

	protected QualityCheck() {
	}

	public QualityCheck(Float blurScore, boolean blurry, Integer resolutionWidth, Integer resolutionHeight,
			boolean passesMinResolution, Boolean framedProperly, String framingIssue) {
		this.blurScore = blurScore;
		this.blurry = blurry;
		this.resolutionWidth = resolutionWidth;
		this.resolutionHeight = resolutionHeight;
		this.passesMinResolution = passesMinResolution;
		this.framedProperly = framedProperly;
		this.framingIssue = framingIssue;
	}

	/** Vision AI의 구도 판단 결과를 결합한 새 QualityCheck 를 반환한다 (불변 값 객체 갱신). */
	public QualityCheck withFraming(Boolean framedProperly, String framingIssue) {
		return new QualityCheck(blurScore, blurry, resolutionWidth, resolutionHeight, passesMinResolution,
				framedProperly, framingIssue);
	}

	public Float getBlurScore() {
		return blurScore;
	}

	public boolean isBlurry() {
		return blurry;
	}

	public Integer getResolutionWidth() {
		return resolutionWidth;
	}

	public Integer getResolutionHeight() {
		return resolutionHeight;
	}

	public boolean isPassesMinResolution() {
		return passesMinResolution;
	}

	public Boolean isFramedProperly() {
		return framedProperly;
	}

	public String getFramingIssue() {
		return framingIssue;
	}
}

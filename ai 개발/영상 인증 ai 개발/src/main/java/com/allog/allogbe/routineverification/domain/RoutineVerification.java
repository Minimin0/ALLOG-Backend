package com.allog.allogbe.routineverification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 루틴 수행 인증 제출 1건. AI(visionAnalysis)는 제안만 하며, reviewStatus 의 최종 확정
 * (VALID_CONFIRMED/INVALIDATED)은 상호신고 + 운영자 검토를 거쳐야 한다.
 *
 * userId/challengeId/participationId 는 아직 구현되지 않은 User/Challenge/Participation
 * 도메인에 대한 ID 참조만 가진다 (연동 필요 지점, STAGE1/2 보고 참고).
 */
@Entity
@Table(name = "routine_verification")
public class RoutineVerification extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false)
	private Long challengeId;

	@Column(nullable = false)
	private Long participationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SubmissionType submissionType;

	@Column(nullable = false, length = 500)
	private String mediaUrl;

	@Column(length = 500)
	private String capturedFrameUrl;

	@Column(nullable = false)
	private LocalDateTime submittedAt;

	@Embedded
	private MetadataCheck metadataCheck;

	@Embedded
	private VisionAnalysisResult visionAnalysis;

	@Embedded
	private QualityCheck qualityCheck;

	@Enumerated(EnumType.STRING)
	@Column(length = 30)
	private AiClassification aiClassification;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ReviewStatus reviewStatus;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReviewPriority reviewPriority;

	@Column(nullable = false)
	private boolean countedInScore;

	private LocalDateTime reviewedAt;

	private Long reviewedBy;

	protected RoutineVerification() {
	}

	public RoutineVerification(Long userId, Long challengeId, Long participationId,
			SubmissionType submissionType, String mediaUrl, LocalDateTime submittedAt,
			MetadataCheck metadataCheck) {
		this.userId = userId;
		this.challengeId = challengeId;
		this.participationId = participationId;
		this.submissionType = submissionType;
		this.mediaUrl = mediaUrl;
		this.submittedAt = submittedAt;
		this.metadataCheck = metadataCheck;
		this.reviewStatus = ReviewStatus.PENDING;
		this.reviewPriority = ReviewPriority.NORMAL;
		this.countedInScore = false;
	}

	/** STAGE7 파이프라인(품질/중복/Vision/규칙엔진) 산출 결과를 반영한다. reviewStatus 는 AUTO_VALID/FLAGGED_FOR_REVIEW 까지만 온다. */
	public void applyClassificationResult(MetadataCheck metadataCheck, VisionAnalysisResult visionAnalysis,
			QualityCheck qualityCheck, AiClassification aiClassification, ReviewStatus reviewStatus,
			ReviewPriority reviewPriority, boolean countedInScore) {
		this.metadataCheck = metadataCheck;
		this.visionAnalysis = visionAnalysis;
		this.qualityCheck = qualityCheck;
		this.aiClassification = aiClassification;
		this.reviewStatus = reviewStatus;
		this.reviewPriority = reviewPriority;
		this.countedInScore = countedInScore;
	}

	public void applyCapturedFrame(String capturedFrameUrl) {
		this.capturedFrameUrl = capturedFrameUrl;
	}

	/**
	 * 운영자 전용 최종 확정(STAGE8 관리자 API에서만 호출되어야 한다).
	 * VALID_CONFIRMED/INVALIDATED/RESUBMIT_REQUESTED 로만 전환 가능하며, 이미 최종 확정된 건은
	 * 재전환할 수 없다. VALID_CONFIRMED 는 isCountedInScore=true, 그 외(무효화/재제출 요청)는
	 * false 로 정리한다 — 실제 달성률 집계/회수는 이 값을 참조하는 STAGE9 이벤트 구독자의 책임이다.
	 */
	public void confirmFinalReview(ReviewStatus finalStatus, Long reviewedBy) {
		if (finalStatus != ReviewStatus.VALID_CONFIRMED
				&& finalStatus != ReviewStatus.INVALIDATED
				&& finalStatus != ReviewStatus.RESUBMIT_REQUESTED) {
			throw new IllegalArgumentException("운영자 확정 상태로 허용되지 않는 값입니다: " + finalStatus);
		}
		if (this.reviewStatus == ReviewStatus.VALID_CONFIRMED
				|| this.reviewStatus == ReviewStatus.INVALIDATED
				|| this.reviewStatus == ReviewStatus.RESUBMIT_REQUESTED) {
			throw new IllegalStateException("이미 최종 확정된 인증입니다: " + this.reviewStatus);
		}
		this.reviewStatus = finalStatus;
		this.reviewedAt = LocalDateTime.now();
		this.reviewedBy = reviewedBy;
		this.countedInScore = (finalStatus == ReviewStatus.VALID_CONFIRMED);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getChallengeId() {
		return challengeId;
	}

	public Long getParticipationId() {
		return participationId;
	}

	public SubmissionType getSubmissionType() {
		return submissionType;
	}

	public String getMediaUrl() {
		return mediaUrl;
	}

	public String getCapturedFrameUrl() {
		return capturedFrameUrl;
	}

	public LocalDateTime getSubmittedAt() {
		return submittedAt;
	}

	public MetadataCheck getMetadataCheck() {
		return metadataCheck;
	}

	public VisionAnalysisResult getVisionAnalysis() {
		return visionAnalysis;
	}

	public QualityCheck getQualityCheck() {
		return qualityCheck;
	}

	public AiClassification getAiClassification() {
		return aiClassification;
	}

	public ReviewStatus getReviewStatus() {
		return reviewStatus;
	}

	public ReviewPriority getReviewPriority() {
		return reviewPriority;
	}

	public boolean isCountedInScore() {
		return countedInScore;
	}

	public LocalDateTime getReviewedAt() {
		return reviewedAt;
	}

	public Long getReviewedBy() {
		return reviewedBy;
	}
}

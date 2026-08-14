package com.allog.allogbe.routineverification.service;

import com.allog.allogbe.routineverification.classification.RoutineVerificationClassificationInput;
import com.allog.allogbe.routineverification.classification.RoutineVerificationClassificationOutput;
import com.allog.allogbe.routineverification.classification.RoutineVerificationClassificationPipeline;
import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.domain.ReviewPriority;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.RoutineVerification;
import com.allog.allogbe.routineverification.domain.SubmissionType;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitCommand;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitRequest;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitResponse;
import com.allog.allogbe.routineverification.event.RoutineVerificationClassifiedEvent;
import com.allog.allogbe.routineverification.event.RoutineVerificationScoreCountingChangedEvent;
import com.allog.allogbe.routineverification.repository.RoutineVerificationRepository;
import com.allog.allogbe.routineverification.storage.MediaStorageException;
import com.allog.allogbe.routineverification.storage.RoutineVerificationMediaStoragePort;
import com.allog.allogbe.routineverification.vision.ChallengeVisionContext;
import com.allog.allogbe.routineverification.vision.ChallengeVisionContextProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 제출 접수 유스케이스. STAGE3 게이트 -> 미디어 저장 -> 최초 저장(PENDING) -> STAGE7 분류 순으로 동기 처리한다.
 *
 * ⚠️ VIDEO/APP_RECORD 는 이번 스테이지에서 완전히 배선하지 못했다 (아래 각 분기 주석 참고) — 지시서에
 * 명시되지 않은 보수적 기본값(REVIEW_REQUIRED)으로 처리했으며, 확정 전 논의가 필요한 설계 판단이다.
 */
@Service
public class RoutineVerificationSubmissionService {

	private final RoutineVerificationSubmissionGate gate;
	private final RoutineVerificationMediaStoragePort mediaStorage;
	private final RoutineVerificationRepository repository;
	private final ChallengeVisionContextProvider visionContextProvider;
	private final RoutineVerificationClassificationPipeline pipeline;
	private final ApplicationEventPublisher eventPublisher;

	public RoutineVerificationSubmissionService(
			RoutineVerificationSubmissionGate gate,
			RoutineVerificationMediaStoragePort mediaStorage,
			RoutineVerificationRepository repository,
			ChallengeVisionContextProvider visionContextProvider,
			RoutineVerificationClassificationPipeline pipeline,
			ApplicationEventPublisher eventPublisher) {
		this.gate = gate;
		this.mediaStorage = mediaStorage;
		this.repository = repository;
		this.visionContextProvider = visionContextProvider;
		this.pipeline = pipeline;
		this.eventPublisher = eventPublisher;
	}

	public RoutineVerificationSubmitResponse submit(RoutineVerificationSubmitCommand command) {
		RoutineVerificationSubmitRequest gateRequest = new RoutineVerificationSubmitRequest(
				command.userId(), command.challengeId(), command.participationId(),
				command.submissionType(), null, command.submittedAt());
		gate.validate(gateRequest);

		String mediaUrl = mediaStorage.store(command.file());

		RoutineVerification verification = new RoutineVerification(
				command.userId(), command.challengeId(), command.participationId(),
				command.submissionType(), mediaUrl, command.submittedAt(),
				new MetadataCheck(true, false, null));
		RoutineVerification saved = repository.save(verification);

		classifyAndUpdate(saved, command);

		return new RoutineVerificationSubmitResponse(saved.getId(), saved.getReviewStatus(),
				"제출이 접수되었습니다. AI 1차 검토 결과가 함께 반영되었습니다.");
	}

	private void classifyAndUpdate(RoutineVerification verification, RoutineVerificationSubmitCommand command) {
		if (verification.getSubmissionType() == SubmissionType.APP_RECORD) {
			// 이미지가 없어 Vision 분석 대상이 아니다. 자동 승인 근거가 없으므로 보수적으로 사람 확인이
			// 필요하다고 처리했다 — 지시서에 명시되지 않은 판단이며 확정이 필요하다.
			applyFallback(verification);
			return;
		}
		if (verification.getSubmissionType() == SubmissionType.VIDEO) {
			// 연동 필요 지점: 영상 길이 프로빙(VideoDurationProbe)이 미구현이라 프레임 추출 배선을
			// 이번 스테이지에서는 보류했다. STAGE4의 프레임 추출 자체는 구현되어 있다.
			applyFallback(verification);
			return;
		}

		ChallengeVisionContext context = visionContextProvider.getContext(verification.getChallengeId());
		BufferedImage image = decodeImage(command.file());

		RoutineVerificationClassificationInput input = new RoutineVerificationClassificationInput(
				new RoutineVerificationSubmitRequest(
						verification.getUserId(), verification.getChallengeId(), verification.getParticipationId(),
						verification.getSubmissionType(), verification.getMediaUrl(), verification.getSubmittedAt()),
				image, command.file().getContentType(),
				context.category(), context.routineDescription(), context.expectedObjects());

		RoutineVerificationClassificationOutput output = pipeline.process(input);

		verification.applyClassificationResult(
				output.metadataCheck(), output.visionAnalysis(), output.qualityCheck(),
				output.decision().aiClassification(), output.decision().reviewStatus(),
				output.decision().reviewPriority(), output.decision().countedInScore());
		repository.save(verification);
		publishClassificationEvents(verification);
	}

	private void applyFallback(RoutineVerification verification) {
		verification.applyClassificationResult(
				verification.getMetadataCheck(), null, null,
				AiClassification.REVIEW_REQUIRED, ReviewStatus.FLAGGED_FOR_REVIEW, ReviewPriority.NORMAL, false);
		repository.save(verification);
		publishClassificationEvents(verification);
	}

	/**
	 * STAGE9 연동 지점: 달성률 계산 서비스(countedInScore 변경 시)와 ④ 코칭 기능(분류 결과 전반)이
	 * 구독할 수 있도록 이벤트만 발행한다 — 실제 구독/처리 로직은 이 모듈의 책임이 아니다.
	 */
	private void publishClassificationEvents(RoutineVerification verification) {
		eventPublisher.publishEvent(new RoutineVerificationClassifiedEvent(
				verification.getId(), verification.getUserId(), verification.getChallengeId(),
				verification.getAiClassification(), verification.getReviewStatus(), LocalDateTime.now()));

		if (verification.isCountedInScore()) {
			eventPublisher.publishEvent(new RoutineVerificationScoreCountingChangedEvent(
					verification.getId(), verification.getUserId(), verification.getChallengeId(),
					verification.getParticipationId(), true, LocalDateTime.now()));
		}
	}

	private BufferedImage decodeImage(MultipartFile file) {
		try {
			return ImageIO.read(file.getInputStream());
		} catch (IOException e) {
			throw new MediaStorageException("이미지 디코딩 실패", e);
		}
	}
}

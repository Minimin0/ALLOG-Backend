package com.allog.allogbe.routineverification.e2e;

import com.allog.allogbe.routineverification.domain.AiClassification;
import com.allog.allogbe.routineverification.domain.ReviewPriority;
import com.allog.allogbe.routineverification.domain.ReviewStatus;
import com.allog.allogbe.routineverification.domain.SubmissionType;
import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import com.allog.allogbe.routineverification.dto.RoutineVerificationDetailResponse;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitResponse;
import com.allog.allogbe.routineverification.duplicate.HashedSubmission;
import com.allog.allogbe.routineverification.duplicate.PerceptualHash;
import com.allog.allogbe.routineverification.duplicate.PerceptualHashCalculator;
import com.allog.allogbe.routineverification.policy.ChallengeVerificationPolicy;
import com.allog.allogbe.routineverification.vision.ChallengeCategory;
import com.allog.allogbe.routineverification.vision.ChallengeVisionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STAGE10: 실제 Spring 컨텍스트(내장 톰캣 + H2)를 띄워 업로드(HTTP multipart) -> 분류 -> 조회(GET)
 * 까지 진짜 엔드투엔드로 검증한다. STAGE7의 6개 필수 케이스를 통합 환경에서 재검증한다.
 *
 * Challenge 도메인(정책/카테고리)·중복이력·Vision API 는 아직 실제 구현이 없어(연동 필요 지점),
 * 이 테스트 전용 대역(Fake*)으로 대체했다 — 실제 Spring 빈 배선·JPA 매핑·HTTP 계층은 전부 진짜다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(classes = {
		FakeChallengeVerificationPolicyProvider.Config.class,
		FakeSubmissionHashHistoryProvider.Config.class,
		FakeVisionAnalysisClient.Config.class,
		FakeChallengeVisionContextProvider.Config.class
})
class RoutineVerificationEndToEndTest {

	private static final Long DEFAULT_CHALLENGE_ID = 1L;
	private static final Long USER_ID = 100L;
	private static final Long PARTICIPATION_ID = 200L;

	@Autowired
	private TestRestTemplate restTemplate;
	@Autowired
	private FakeChallengeVerificationPolicyProvider policyProvider;
	@Autowired
	private FakeSubmissionHashHistoryProvider hashHistoryProvider;
	@Autowired
	private FakeVisionAnalysisClient visionClient;
	@Autowired
	private FakeChallengeVisionContextProvider contextProvider;
	@Autowired
	private PerceptualHashCalculator hashCalculator;

	@BeforeEach
	void setUp() {
		visionClient.reset();
		hashHistoryProvider.reset();
		policyProvider.register(DEFAULT_CHALLENGE_ID, new ChallengeVerificationPolicy(
				Set.of(SubmissionType.PHOTO, SubmissionType.VIDEO, SubmissionType.APP_RECORD),
				LocalTime.MIN, LocalTime.MAX));
		contextProvider.register(DEFAULT_CHALLENGE_ID,
				new ChallengeVisionContext(ChallengeCategory.EXERCISE, "운동 인증", List.of("운동화")));
	}

	/** 500x500 고대비 체커보드 — 새 화질 게이트(최소 해상도 480px, 선명도)를 통과하도록 크고 또렷하게 만든다. */
	private byte[] jpegBytes(int seed) throws IOException {
		int size = 500;
		int blockSize = 10;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				boolean black = ((x / blockSize) + (y / blockSize) + seed) % 2 == 0;
				int gray = black ? 10 : 240;
				image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	private ResponseEntity<RoutineVerificationSubmitResponse> submitPhoto(
			Long challengeId, byte[] photoBytes) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("userId", USER_ID);
		body.add("participationId", PARTICIPATION_ID);
		body.add("submissionType", SubmissionType.PHOTO.name());
		body.add("file", namedResource(photoBytes, "photo.jpg"));

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

		return restTemplate.postForEntity(
				"/api/v1/challenges/{challengeId}/verifications", request,
				RoutineVerificationSubmitResponse.class, challengeId);
	}

	/** 500x500 이지만 대비가 거의 없는 매끈한 이미지 — 화질 게이트에서 흐린 이미지로 걸러져야 한다. */
	private byte[] blurryJpegBytes() throws IOException {
		int size = 500;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int gray = 128 + (int) (2 * Math.sin(x / 400.0) + 2 * Math.cos(y / 400.0));
				image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	private ByteArrayResource namedResource(byte[] bytes, String filename) {
		return new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return filename;
			}
		};
	}

	private RoutineVerificationDetailResponse getDetail(Long id) {
		return restTemplate.getForObject("/api/v1/verifications/{id}", RoutineVerificationDetailResponse.class, id);
	}

	@Test
	void 케이스1_정상_제출은_업로드부터_조회까지_PASS로_귀결된다() throws IOException {
		visionClient.respondWith(new VisionAnalysisResult(
				true, List.of("운동화"), 0.9, List.of(), 0.9, "운동 중인 모습이 관찰됩니다."));

		ResponseEntity<RoutineVerificationSubmitResponse> submitResponse =
				submitPhoto(DEFAULT_CHALLENGE_ID, jpegBytes(1));

		assertThat(submitResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		Long id = submitResponse.getBody().verificationId();

		RoutineVerificationDetailResponse detail = getDetail(id);
		assertThat(detail.aiClassification()).isEqualTo(AiClassification.PASS);
		assertThat(detail.reviewStatus()).isEqualTo(ReviewStatus.AUTO_VALID);
		assertThat(detail.countedInScore()).isTrue();
		assertThat(visionClient.callCount()).isEqualTo(1);
	}

	@Test
	void 케이스2_시간_외_제출은_422로_즉시_거부되고_AI가_호출되지_않는다() throws IOException {
		Long timeRestrictedChallengeId = 2L;
		LocalTime unreachableInstant = LocalTime.now().plusHours(12); // "지금"과 절대 겹치지 않는 순간
		policyProvider.register(timeRestrictedChallengeId, new ChallengeVerificationPolicy(
				Set.of(SubmissionType.PHOTO), unreachableInstant, unreachableInstant));
		contextProvider.register(timeRestrictedChallengeId,
				new ChallengeVisionContext(ChallengeCategory.EXERCISE, "운동 인증", List.of("운동화")));

		ResponseEntity<String> response = restTemplateRawPost(timeRestrictedChallengeId, jpegBytes(2));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
		assertThat(response.getBody()).contains("OUTSIDE_VERIFICATION_TIME_WINDOW");
		assertThat(visionClient.callCount()).isZero();
	}

	@Test
	void 케이스3_중복_해시_감지는_REJECT_CANDIDATE_HIGH로_귀결되고_Vision_호출을_생략한다() throws IOException {
		byte[] photo = jpegBytes(3);
		BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(photo));
		PerceptualHash exactHash = hashCalculator.calculate(decoded);
		hashHistoryProvider.seedOwnHistory(new HashedSubmission(9999L, USER_ID, exactHash));

		ResponseEntity<RoutineVerificationSubmitResponse> submitResponse = submitPhoto(DEFAULT_CHALLENGE_ID, photo);
		Long id = submitResponse.getBody().verificationId();

		RoutineVerificationDetailResponse detail = getDetail(id);
		assertThat(detail.aiClassification()).isEqualTo(AiClassification.REJECT_CANDIDATE);
		assertThat(detail.reviewPriority()).isEqualTo(ReviewPriority.HIGH);
		assertThat(detail.metadataCheck().isDuplicate()).isTrue();
		assertThat(visionClient.callCount()).isZero();
	}

	@Test
	void 케이스4_기대_객체_미탐지는_REJECT_CANDIDATE_HIGH로_귀결된다() throws IOException {
		visionClient.respondWith(new VisionAnalysisResult(
				false, List.of("고양이"), 0.1, List.of(), 0.8, "운동과 무관한 이미지입니다."));

		ResponseEntity<RoutineVerificationSubmitResponse> submitResponse =
				submitPhoto(DEFAULT_CHALLENGE_ID, jpegBytes(4));
		Long id = submitResponse.getBody().verificationId();

		RoutineVerificationDetailResponse detail = getDetail(id);
		assertThat(detail.aiClassification()).isEqualTo(AiClassification.REJECT_CANDIDATE);
		assertThat(detail.reviewPriority()).isEqualTo(ReviewPriority.HIGH);
	}

	@Test
	void 케이스5_이상징후가_있으면_REJECT_CANDIDATE_HIGH로_귀결된다() throws IOException {
		visionClient.respondWith(new VisionAnalysisResult(
				true, List.of("운동화"), 0.9, List.of("화면 재촬영 의심"), 0.7, "이상 징후가 발견되었습니다."));

		ResponseEntity<RoutineVerificationSubmitResponse> submitResponse =
				submitPhoto(DEFAULT_CHALLENGE_ID, jpegBytes(5));
		Long id = submitResponse.getBody().verificationId();

		RoutineVerificationDetailResponse detail = getDetail(id);
		assertThat(detail.aiClassification()).isEqualTo(AiClassification.REJECT_CANDIDATE);
		assertThat(detail.reviewPriority()).isEqualTo(ReviewPriority.HIGH);
		assertThat(detail.countedInScore()).isFalse();
	}

	@Test
	void 케이스6_비허용_submissionType은_400으로_즉시_거부되고_AI가_호출되지_않는다() throws IOException {
		Long photoDisallowedChallengeId = 6L;
		policyProvider.register(photoDisallowedChallengeId, new ChallengeVerificationPolicy(
				Set.of(SubmissionType.VIDEO), LocalTime.MIN, LocalTime.MAX));
		contextProvider.register(photoDisallowedChallengeId,
				new ChallengeVisionContext(ChallengeCategory.EXERCISE, "운동 인증", List.of("운동화")));

		ResponseEntity<String> response = restTemplateRawPost(photoDisallowedChallengeId, jpegBytes(6));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("DISALLOWED_SUBMISSION_TYPE");
		assertThat(visionClient.callCount()).isZero();
	}

	@Test
	void 보너스_VIDEO_제출은_아직_미배선이라_REVIEW_REQUIRED로_보수적_처리된다() throws IOException {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("userId", USER_ID);
		body.add("participationId", PARTICIPATION_ID);
		body.add("submissionType", SubmissionType.VIDEO.name());
		body.add("file", namedResource(new byte[]{1, 2, 3, 4}, "video.mp4"));

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

		ResponseEntity<RoutineVerificationSubmitResponse> submitResponse = restTemplate.postForEntity(
				"/api/v1/challenges/{challengeId}/verifications", request,
				RoutineVerificationSubmitResponse.class, DEFAULT_CHALLENGE_ID);
		Long id = submitResponse.getBody().verificationId();

		RoutineVerificationDetailResponse detail = getDetail(id);
		assertThat(detail.reviewStatus()).isEqualTo(ReviewStatus.FLAGGED_FOR_REVIEW);
		assertThat(visionClient.callCount()).isZero();
	}

	@Test
	void 케이스7_흐린_이미지는_422_LOW_QUALITY_BLUR로_즉시_거부되고_중복검사와_AI가_호출되지_않는다() throws IOException {
		ResponseEntity<String> response = restTemplateRawPost(DEFAULT_CHALLENGE_ID, blurryJpegBytes());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
		assertThat(response.getBody()).contains("LOW_QUALITY_BLUR");
		assertThat(visionClient.callCount()).isZero();
	}

	@Test
	void 케이스8_구도가_잘못되었다고_판단되어도_PASS로_귀결되고_구도_정보는_기록만_남는다() throws IOException {
		visionClient.respondWith(new VisionAnalysisResult(
				true, List.of("운동화"), 0.9, List.of(), 0.8, "운동화가 화면 절반만 보입니다.",
				false, "인물이 화면 밖으로 크게 잘려나가 있습니다."));

		ResponseEntity<RoutineVerificationSubmitResponse> submitResponse =
				submitPhoto(DEFAULT_CHALLENGE_ID, jpegBytes(8));
		Long id = submitResponse.getBody().verificationId();

		RoutineVerificationDetailResponse detail = getDetail(id);
		assertThat(detail.aiClassification()).isEqualTo(AiClassification.PASS);
		assertThat(detail.qualityCheck().isFramedProperly()).isFalse();
		assertThat(detail.qualityCheck().framingIssue()).contains("잘려나가");
	}

	private ResponseEntity<String> restTemplateRawPost(Long challengeId, byte[] photoBytes) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("userId", USER_ID);
		body.add("participationId", PARTICIPATION_ID);
		body.add("submissionType", SubmissionType.PHOTO.name());
		body.add("file", namedResource(photoBytes, "photo.jpg"));

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

		return restTemplate.postForEntity(
				"/api/v1/challenges/{challengeId}/verifications", request, String.class, challengeId);
	}
}

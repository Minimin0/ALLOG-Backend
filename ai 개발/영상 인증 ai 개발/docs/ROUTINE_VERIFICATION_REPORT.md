# ALLOG 루틴 인증 1차 분석 AI 에이전트 — 최종 결과보고

STAGE 1~10 산출물을 취합한 최종 기술 문서. 스코프: ③ 인증 1차 분석(Vision AI)만 해당. ①②④⑤(온보딩 추천/오픈 그룹 매칭/진행 페이스 코칭/AAC 혜택 추천)은 구현하지 않았다.

## 1. 기존 코드 스캔 결과 요약 (STAGE 1)

`ALLOG_BE`는 착수 시점에 **완전한 그린필드 프로젝트**였다 (`.claude` 설정 폴더 외 파일 없음). 따라서:
- 기존 챌린지/참여(Participation) 엔티티, 파일 업로드 연동, AI 연동, 달성률 계산 서비스, 신고(Report) 도메인, 챌린지 템플릿 카테고리 필드 — **모두 존재하지 않음**.
- 구버전 잔존 코드 없음.
- 이 세션에서 Gradle/Spring Boot 프로젝트 골격 자체를 처음부터 구성했다 (JDK/Gradle이 로컬에 없어 IntelliJ가 캐시해 둔 JDK 21 + Gradle 9.6.1을 재사용).

## 2. 변경/신규 파일 전체 목록

### 빌드/설정
- `build.gradle`, `settings.gradle`, `gradle.properties`
- `src/main/resources/application.yml`, `src/test/resources/application-test.yml`

### 도메인 (`domain/`)
`RoutineVerification`, `BaseTimeEntity`, `MetadataCheck`, `VisionAnalysisResult`, `StringListJsonConverter`, `SubmissionType`, `ReviewStatus`, `ReviewPriority`, `AiClassification`

### 제출 검증 게이트 (`service/`, `exception/`)
`RoutineVerificationSubmissionGate`, `DisallowedSubmissionTypeException`, `OutsideVerificationTimeWindowException`, `RoutineVerificationNotFoundException`, `InvalidAdminReviewStatusException`, `RoutineVerificationErrorResponse`, `RoutineVerificationExceptionHandler`

### 정책 포트 (`policy/`)
`ChallengeVerificationPolicy`, `ChallengeVerificationPolicyProvider` (연동 필요 지점)

### 미디어 처리 (`media/`)
`VideoFrameExtractor`, `FfmpegVideoFrameExtractor`, `RoutineVerificationFrameCaptureService`, `FrameExtractionAttemptException`, `FrameCaptureException`

### 중복 탐지 (`duplicate/`)
`PerceptualHash`, `PerceptualHashCalculator`, `HashedSubmission`, `SubmissionHashHistoryProvider`(연동 필요 지점), `DuplicateCheckResult`, `RoutineVerificationDuplicateDetector`

### Vision AI 연동 (`vision/`)
`ChallengeCategory`, `ChallengeVisionContext`, `ChallengeVisionContextProvider`(연동 필요 지점), `VisionAnalysisRequest`, `VisionAnalysisAttemptException`, `VisionAnalysisClient`, `VisionAnalysisOutcome`, `RoutineVerificationVisionAnalysisService`, `VisionAnalysisToolSchema`, `VisionAnalysisPromptBuilder`, `VisionAnalysisToolResponseParser`, `ClaudeVisionAnalysisClient`(⚠️ 미실행검증)

### 분류 결정 (`classification/`)
`ClassificationDecision`, `RoutineVerificationClassificationRuleEngine`, `RoutineVerificationClassificationInput/Output`, `RoutineVerificationClassificationPipeline`

### API (`controller/`, `dto/`)
`RoutineVerificationController`, `controller/admin/RoutineVerificationAdminController`, `RoutineVerificationSubmitCommand/Request/Response`, `RoutineVerificationDetailResponse`, `MetadataCheckResponse`, `VisionAnalysisResponse`, `RoutineVerificationDetailMapper`, `RoutineVerificationAdminUpdateRequest/Response`, `RoutineVerificationQueueItemResponse`

### 저장소 (`storage/`)
`RoutineVerificationMediaStoragePort`, `LocalTempRoutineVerificationMediaStorage`(⚠️ 임시 구현, 연동 필요 지점), `MediaStorageException`

### 서비스 계층 (`service/`)
`RoutineVerificationSubmissionService`, `RoutineVerificationAdminReviewService`

### 시스템 연동 (`event/`, `insight/`)
`RoutineVerificationScoreCountingChangedEvent`, `RoutineVerificationClassifiedEvent`, `RoutineVerificationSummary`, `RoutineVerificationInsightsQueryService`

### 리포지토리
`RoutineVerificationRepository`

### 테스트 (28개 클래스, `src/test/java/...` 동일 패키지 구조 + `e2e/`)
단위 테스트 24개 클래스 + `e2e/` 패키지(`RoutineVerificationEndToEndTest`, `FakeChallengeVerificationPolicyProvider`, `FakeSubmissionHashHistoryProvider`, `FakeVisionAnalysisClient`, `FakeChallengeVisionContextProvider`)

## 3. 신규 API 엔드포인트 명세

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/challenges/{challengeId}/verifications` | 루틴 인증 제출 (multipart) → 202 |
| GET | `/api/v1/verifications/{id}` | 상세 조회 |
| GET | `/api/v1/admin/verifications?status=` | 운영자 검토 큐 (우선순위 정렬) |
| PATCH | `/api/v1/admin/verifications/{id}` | 최종 확정 (VALID_CONFIRMED/INVALIDATED/RESUBMIT_REQUESTED) |
| POST | `/api/v1/verifications/{id}/reports` | **미구현** — Report 도메인 없음(STAGE1 확인), 연동 필요 지점 |

요청/응답 예시는 STAGE8 완료 보고에 상세 기재(본 문서에서는 생략, 대화 이력 참고). 요약:
```
POST /api/v1/challenges/1/verifications  (multipart: userId, participationId, submissionType, file)
→ 202 { "verificationId": 501, "reviewStatus": "AUTO_VALID", "message": "..." }

GET /api/v1/verifications/501
→ 200 { ..., "aiClassification": "PASS", "reviewStatus": "AUTO_VALID", "countedInScore": true, ... }

PATCH /api/v1/admin/verifications/501  { "targetStatus": "VALID_CONFIRMED", "reviewedBy": 999 }
→ 200 { "id": 501, "reviewStatus": "VALID_CONFIRMED", "reviewedAt": "...", "reviewedBy": 999 }
```

## 4. 전체 테스트 실행 결과 (STAGE 3~10 취합, 클린 빌드 기준)

**전체 77/77 통과, 0 실패.**

| 스테이지 | 클래스 | 테스트 수 |
|---|---|---|
| STAGE3 | RoutineVerificationSubmissionGateTest | 8 |
| STAGE4 | RoutineVerificationFrameCaptureServiceTest | 4 |
| STAGE5 | PerceptualHashCalculatorTest, RoutineVerificationDuplicateDetectorTest | 3 + 5 |
| STAGE6 | VisionAnalysisPromptBuilderTest, VisionAnalysisToolResponseParserTest, RoutineVerificationVisionAnalysisServiceTest | 9 + 7 + 3 |
| STAGE7 | RoutineVerificationClassificationRuleEngineTest, RoutineVerificationClassificationPipelineTest | 7 + 7 |
| STAGE8 | RoutineVerificationControllerTest, RoutineVerificationAdminControllerTest, RoutineVerificationSubmissionServiceTest, RoutineVerificationAdminReviewServiceTest | 3+2+4+7 |
| STAGE9 | RoutineVerificationInsightsQueryServiceTest (+ 이벤트 발행 케이스는 위 서비스 테스트에 포함) | 1 |
| STAGE10 | **RoutineVerificationEndToEndTest** (실제 HTTP+H2, STAGE7 6개 필수 케이스 재검증 + VIDEO 보너스) | 7 |

STAGE10 E2E 과정에서 발견·수정한 실제 버그 1건: `VisionAnalysisResult`의 JSON 컬럼 정의가 H2에서 저장 후 재조회 시 파싱 실패 → `TEXT`로 수정(프로덕션 코드 반영 완료).

## 5. 미정 사항 목록

| 항목 | 현재 상태 | 비고 |
|---|---|---|
| `RELEVANCE_THRESHOLD`(0.5, STAGE7) | 코드에 상수로 존재, **실데이터 미검증** | 운영 데이터로 재보정 필요 |
| `HAMMING_DISTANCE_THRESHOLD`(10) / `DUPLICATE_CHECK_WINDOW`(7일, STAGE5) | 코드에 상수로 존재, **실데이터 미검증** | 동일 |
| 원본 영상 파일 보관 기간(TTL) | **미정, TODO만 명시** | `LocalTempRoutineVerificationMediaStorage`는 임시 로컬 저장이며 자동 삭제 없음 — 영구 저장 금지 원칙과 충돌 방지 위해 정책 확정 시급 |
| 카테고리별 기대 객체 목록 최종안 | `ChallengeCategory` enum(4종)만 정의, 실제 매핑 값은 Challenge 도메인 미구현으로 미확정 | |
| VIDEO/APP_RECORD 실제 파이프라인 배선 | **미배선**, 보수적으로 REVIEW_REQUIRED 처리 (STAGE8에서 명시적으로 확인 요청한 설계 판단, 아직 승인 대기) | ffprobe 상당 컴포넌트(VideoDurationProbe) 부재 |
| 파일 업로드 저장 방식 | 로컬 임시 디스크(연동 필요 지점) | S3/GCS 등 오브젝트 스토리지로 교체 필요 |
| 202 Accepted의 동기/비동기 여부 | 현재 완전 동기 처리 | Vision API 지연 커지면 비동기 전환 검토 |
| 인증/인가 | 없음 — `userId`/`reviewedBy`를 요청 파라미터/바디로 임시 수령 | 운영자 전용 API 접근 제어 공백 |

## 6. 남은 리스크 및 한계

- **pHash 한계**: 각도/조명이 크게 다른 동일 행위 사진, 심한 크롭/회전에는 탐지력이 약함(구조상 원천적 한계). 임계치(10)는 실데이터로 검증되지 않은 초기값.
- **Vision AI 오탐 가능성**: 프롬프트로 확정적 판정 표현을 금지했지만, 모델이 여전히 암묵적으로 판단 편향된 언어를 사용할 가능성은 완전히 배제할 수 없음. 실제 API 미검증(키 없음)이라 실측 오탐률 데이터 없음.
- **객체탐지 정확도**: 실제 Vision 모델을 이번 세션에서 한 번도 호출하지 못해(API 키 부재) 정확도를 실측하지 못함.
- **미실행 검증 어댑터 2건**: `ClaudeVisionAnalysisClient`(API 키/네트워크 필요), `FfmpegVideoFrameExtractor`(ffmpeg 바이너리 필요) — 코드는 작성되었으나 실제 호출 검증 없음.
- **보안 공백**: 운영자 전용 API(`/admin/*`)에 대한 권한 검증이 없어, 현재 구조로는 누구나 최종 확정 API를 호출할 수 있음. 프로덕션 배포 전 반드시 인증/인가 연동 필요.
- **VIDEO 파이프라인 미완성**: 실제 영상 제출은 현재 전부 사람 검토(REVIEW_REQUIRED)로 빠지며, STAGE4의 프레임 추출 로직이 실제로 연결되어 동작하지 않음.

## 7. ①②④⑤ 영역과의 연동 필요 지점

| 대상 도메인 | 연동 필요 지점 | 비고 |
|---|---|---|
| Challenge/ChallengeTemplate (①과 인접) | `ChallengeVerificationPolicyProvider`, `ChallengeVisionContextProvider` 구현체 | 카테고리/기대객체/인증시간window 실데이터 필요 |
| User/인증·인가 | userId/reviewedBy 인증 주체 연동 | 전역 |
| Report(신고) 도메인 | `POST /verifications/{id}/reports` 미구현 | STAGE1에서 부재 확인 |
| 달성률(개인/그룹) 계산 서비스 | `RoutineVerificationScoreCountingChangedEvent` 구독 | STAGE9에서 이벤트 발행만 준비 |
| ④ 진행 페이스 코칭 | `RoutineVerificationClassifiedEvent` 구독 또는 `RoutineVerificationInsightsQueryService.findRecentSummaries()` 호출 | 코칭 로직 자체는 미구현(스코프 밖) |
| ① 온보딩 챌린지 추천 / ② 오픈 그룹 매칭 / ⑤ AAC 혜택 추천 | 접촉 없음 | 이번 세션에서 전혀 구현하지 않음 |

---

## 완료조건 / PASS-FAIL 판정

- [x] STAGE 1~11 전부 완료, STAGE 2 설계 승인 기록 존재 (사용자 "승인" 확인)
- [x] 6개 필수 테스트 케이스 통과 + 회귀 테스트 통과 + 빌드 성공 (77/77, 클린 빌드)
- [x] AI 모듈이 reviewStatus를 VALID_CONFIRMED/INVALIDATED로 직접 전환하는 코드 경로 없음 — `grep` 결과 해당 값 대입은 `RoutineVerification.confirmFinalReview()` 1곳뿐이며, 호출 경로는 관리자 API(`RoutineVerificationAdminReviewService`)가 유일함을 코드 리뷰로 확인
- [x] ①②④⑤ AI 기능 미구현 확인 — 관련 키워드 grep 결과 주석(연동 지점 설명)만 존재, 실제 로직 없음

**최종 판정: PASS**

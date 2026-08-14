# AI Provider Integration Contract (현재 구현 기준)

이 문서는 "별도 AI 서버"가 아니라, **Spring Backend 내부의 `ClaudeVisionAnalysisClient`가
Anthropic Messages API를 직접 호출하는 Provider Integration 구조**를 설명한다. Backend와 AI
사이에 프로세스 경계나 별도 배포가 없다 — 전부 같은 JVM 프로세스 안에서 일어난다.

**참조 코드 위치** (모두 아래 브랜치/커밋 기준):
- 저장소: `Minimin0/ALLOG-Backend`
- 브랜치: `feature/routine-verification-ai-agent`
- 커밋 SHA: `c6fb8c81fd2ee1dc5d3e3fd10a5b4036858f68fd`
- 코드 루트: `ai 개발/영상 인증 ai 개발/src/main/java/com/allog/allogbe/routineverification/`

| 컴포넌트 | 경로 |
|---|---|
| `ClaudeVisionAnalysisClient` | `vision/ClaudeVisionAnalysisClient.java` |
| `VisionAnalysisResult` | `domain/VisionAnalysisResult.java` |
| Rule engine | `classification/RoutineVerificationClassificationRuleEngine.java` |
| ffmpeg 관련 | `media/FfmpegVideoFrameExtractor.java`, `media/RoutineVerificationFrameCaptureService.java` |

---

## 1. ClaudeVisionAnalysisClient 입력/출력

**입력** — `VisionAnalysisRequest`:
```java
record VisionAnalysisRequest(
    byte[] imageBytes,
    String imageMediaType,      // 예: "image/jpeg"
    ChallengeCategory category, // SKINCARE | MEAL | EXERCISE | SLEEP (4종 고정)
    String routineDescription,  // 자유 형식 문자열
    List<String> expectedObjects // 자유 형식 문자열 목록
)
```

**출력** — 성공 시 `VisionAnalysisResult`(2절), 실패 시 `VisionAnalysisAttemptException`(단일 시도 실패,
재시도 대상 — 7절 참고).

설정값(`application.yml`):
- `allog.vision.anthropic-api-key` (기본값 빈 문자열)
- `allog.vision.model` (기본값 `claude-sonnet-5`)
- 요청 URL: `https://api.anthropic.com/v1/messages` (하드코딩)
- `anthropic-version`: `2023-06-01`
- `max_tokens`: 1024
- HTTP 타임아웃: 30초 (`java.net.http.HttpClient`, 커넥션 풀/재사용 설정 없음, 요청마다 신규 커넥션)

---

## 2. Anthropic request / tool schema

실제로 Anthropic에 보내는 요청 바디 (`ClaudeVisionAnalysisClient.buildRequestBody()`):

```json
{
  "model": "claude-sonnet-5",
  "max_tokens": 1024,
  "tools": [{
    "name": "report_routine_verification_analysis",
    "description": "루틴 인증 이미지에 대한 1차 참고 분석 결과를 구조화된 형태로 보고합니다. 이 도구의 출력은 최종 인증 판정이 아니며 참고용 제안입니다.",
    "input_schema": {
      "type": "object",
      "properties": {
        "objectPresence": { "type": "boolean" },
        "detectedObjects": { "type": "array", "items": { "type": "string" } },
        "relevanceScore": { "type": "number", "minimum": 0, "maximum": 1 },
        "anomalyFlags": { "type": "array", "items": { "type": "string" } },
        "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
        "summary": { "type": "string" },
        "isFramedProperly": { "type": "boolean" },
        "framingIssue": { "type": "string" }
      },
      "required": [
        "objectPresence", "detectedObjects", "relevanceScore",
        "anomalyFlags", "confidence", "summary", "isFramedProperly"
      ]
    }
  }],
  "tool_choice": { "type": "tool", "name": "report_routine_verification_analysis" },
  "messages": [{
    "role": "user",
    "content": [
      { "type": "text", "text": "<VisionAnalysisPromptBuilder.build() 결과 — 카테고리/루틴설명/기대객체 삽입>" },
      { "type": "image", "source": { "type": "base64", "media_type": "image/jpeg", "data": "<base64>" } }
    ]
  }]
}
```

`framingIssue`는 **required 목록에 없다** — `isFramedProperly=false`일 때만 채워지는 선택 필드다.
JSON 스키마 강제 방식은 `tool_choice`로 이 도구 호출을 강제하는 것이며, 모델이 자유 형식 텍스트로
응답할 가능성을 애초에 차단한다(파싱 실패 케이스는 7절 참고).

---

## 3. VisionAnalysisResult

Anthropic 응답의 `tool_use.input`을 `VisionAnalysisToolResponseParser`가 파싱해서 만드는 내부 도메인 객체:

```java
class VisionAnalysisResult {
    Boolean objectPresence;
    List<String> detectedObjects;
    Double relevanceScore;   // 0~1 (파서가 clamp)
    List<String> anomalyFlags;
    Double confidence;       // 0~1 (파서가 clamp)
    String summary;
    Boolean framedProperly;  // isFramedProperly
    String framingIssue;     // nullable
}
```

예시:
```json
{
  "objectPresence": true,
  "detectedObjects": ["운동화", "매트"],
  "relevanceScore": 0.8,
  "anomalyFlags": [],
  "confidence": 0.9,
  "summary": "운동 매트와 운동화가 관찰됩니다.",
  "isFramedProperly": true,
  "framingIssue": null
}
```

`confidence`는 **calibration된 실제 확률이 아니다** — 모델이 프롬프트 지시에 따라 자체 보고하는
값이며, 통계적으로 검증되지 않았다. PASS/REVIEW/REJECT 임계치 판단에는 `confidence`가 아니라
`relevanceScore`가 쓰인다(4절).

---

## 4. Backend rule engine 결과

`RoutineVerificationClassificationRuleEngine.classify()`가 산출하는 `aiClassification`은 3가지뿐이다:

| 값 | 조건(우선순위 순) | reviewStatus | reviewPriority | countedInScore |
|---|---|---|---|---|
| `REJECT_CANDIDATE` | 중복(pHash) 확정 **또는** `objectPresence=false` | `FLAGGED_FOR_REVIEW` | `HIGH` | false |
| `REVIEW_REQUIRED` | Vision 3회 재시도 실패, **또는** `anomalyFlags` 존재, **또는** `relevanceScore < 0.5`, **또는** `isFramedProperly=false` | `FLAGGED_FOR_REVIEW` | `NORMAL` | false |
| `PASS` | 위 조건 전부 해당 없음 | `AUTO_VALID` | `NORMAL` | true |

**PASS/FAIL/UNCERTAIN 같은 이름이 아니다.** 그리고 이 값 자체가 최종 판정이 아니다(10절).
`RELEVANCE_THRESHOLD=0.5`는 실측 데이터로 검증된 값이 아닌 잠정치다(캘리브레이션 이력은
`docs/calibration/` 참고).

---

## 5. PHOTO 처리 흐름

`RoutineVerificationSubmissionService.submit()` → `classifyAndUpdate()` → `RoutineVerificationClassificationPipeline.process()` 순으로 **전부 동기 처리**:

```
1. RoutineVerificationSubmissionGate.validate()         — submissionType/시간창 검증 (STAGE3, 변경 없음)
2. RoutineVerificationMediaStoragePort.store()           — 미디어 저장, mediaUrl 확보
3. RoutineVerification 최초 저장 (reviewStatus=PENDING)
4. RoutineVerificationClassificationPipeline.process():
   a. gate.validate() 재검증
   b. ImageQualityAnalyzer.analyze()  — 규칙 0, 실패 시 즉시 예외(6~7절 아님, 다른 문서 참고)
   c. PerceptualHashCalculator + RoutineVerificationDuplicateDetector — 중복 확인
   d. 중복이 아니면 RoutineVerificationVisionAnalysisService.analyze() 호출 (이번 문서의 본론)
   e. RoutineVerificationClassificationRuleEngine.classify()
5. RoutineVerification.applyClassificationResult() 로 최종 반영 후 재저장
6. RoutineVerificationClassifiedEvent / RoutineVerificationScoreCountingChangedEvent 발행
```

---

## 6. VIDEO 현재 상태

**ffmpeg 프레임 추출 설계는 존재하지만, Vision 파이프라인에 배선되어 있지 않다.**

- 설계된 것: `RoutineVerificationFrameCaptureService`가 영상 중간 → 앞(1/4) → 뒤(3/4) 지점 순으로
  최대 3회 `VideoFrameExtractor`(구현체 `FfmpegVideoFrameExtractor`, ffmpeg CLI 서브프로세스 호출,
  15초 타임아웃)를 시도해 대표 프레임 1장을 뽑는다.
- **실제로 일어나는 일**: `RoutineVerificationSubmissionService.classifyAndUpdate()`는
  `submissionType == VIDEO`면 위 서비스를 전혀 호출하지 않고 곧바로 `applyFallback()`으로 가서
  `aiClassification=REVIEW_REQUIRED`, `reviewStatus=FLAGGED_FOR_REVIEW`를 강제 부여한다.
  **Vision API 호출 자체가 발생하지 않는다.**
- 미구현 이유: 영상 길이를 알아야 중간/앞/뒤 지점을 계산할 수 있는데, 길이를 재는 `VideoDurationProbe`
  같은 컴포넌트가 없다(ffprobe 상당 기능 미구현). 또한 이 개발 환경 자체에 ffmpeg 바이너리가 없어
  `FfmpegVideoFrameExtractor`는 작성만 됐고 실행 검증(처리 시간 포함)도 못 했다.

---

## 7. 현재 retry 동작

`RoutineVerificationVisionAnalysisService.analyze()`:
- 최대 3회(`MAX_ATTEMPTS=3`) **즉시 재시도** — 대기 시간(backoff) 없음, 지수 백오프 없음
- `VisionAnalysisAttemptException`만 잡아서 재시도 (그 외 예외는 전파됨)
- 3회 모두 실패하면 예외를 던지지 않고 `VisionAnalysisOutcome.unavailable()` 반환 → 규칙엔진이
  이를 `REVIEW_REQUIRED`(사용자 귀책 아님으로 간주)로 처리

---

## 8. 현재 error handling의 한계

`VisionAnalysisAttemptException`이 아래 상황을 **전부 하나로 뭉뚱그려서** 던진다 (원인 구분 불가):
- HTTP IO 실패 / 타임아웃 (`ClaudeVisionAnalysisClient`)
- HTTP 상태코드 != 200 (Anthropic 쪽 4xx/5xx 포함, 상태코드 정보가 메시지 문자열에만 있고 구조화되어 있지 않음)
- 응답이 유효한 JSON이 아님
- 기대한 `tool_use` 블록이 없음
- 필수 필드 누락, 타입 불일치 (`VisionAnalysisToolResponseParser`)

**명시적으로 구분되지 않는 것들**:
- "영상 파일 자체가 깨짐/디코드 불가", "지원 안 하는 codec" — VIDEO 미배선이라 해당 처리 경로 자체가 없음
- "행동 미감지"는 에러가 아니라 정상 응답(`objectPresence=false`)으로 처리됨 — 에러 taxonomy 대상이 아님
- "confidence 부족"은 결과값이지 에러가 아님
- **"사용자가 잘못 올린 미디어"와 "Provider(Anthropic) 쪽 장애"가 코드 레벨에서 구분되지 않는다** —
  전부 3회 재시도 후 `REVIEW_REQUIRED`로 수렴한다.

이 갭은 STAGE7 캘리브레이션 보고서(`docs/calibration/`)에서도 별도로 지적됐다. 에러 taxonomy와
`ReviewStatus` 매핑은 이번 문서에 포함하지 않았다 — 별도 계약 합의 후 구현 예정.

---

## 9. category / routineDescription / expectedObjects의 데이터 소스

`ChallengeVisionContextProvider`는 **인터페이스만 존재하고 구현체가 없다**:

```java
public interface ChallengeVisionContextProvider {
    ChallengeVisionContext getContext(Long challengeId);
}
public record ChallengeVisionContext(
    ChallengeCategory category,      // SKINCARE | MEAL | EXERCISE | SLEEP 4종 고정, 세부 루틴코드 체계 없음
    String routineDescription,       // 자유 형식
    List<String> expectedObjects     // 자유 형식
) {}
```

이 값들이 실제로 어디서 오는지(Challenge/ChallengeTemplate 도메인)가 **아직 구현되지 않았다** — Spring
빈으로 등록된 구현체가 없으므로, 이 인터페이스를 실제로 사용하는 경로(PHOTO 흐름)는 현재
Challenge 도메인 구현 전까지는 런타임에 빈을 찾지 못해 애플리케이션이 기동조차 되지 않는다
(테스트에서는 Fake 구현체로 대체됨 — `FakeChallengeVisionContextProvider`).

---

## 10. AI 결과는 최종 Verification 판정이 아니다

`RoutineVerification` 상태머신에서 AI/규칙엔진이 도달할 수 있는 `reviewStatus`는 **`AUTO_VALID`와
`FLAGGED_FOR_REVIEW` 뿐이다.** `VALID_CONFIRMED` / `INVALIDATED` / `RESUBMIT_REQUESTED`로의 전환은
오직 운영자 전용 API(`PATCH /api/v1/admin/verifications/{id}` → `RoutineVerificationAdminReviewService`)
에서만 발생하며, 이 코드 경로는 AI/규칙엔진 코드와 완전히 분리되어 있다(grep으로 확인 가능 — 해당
값 대입은 `RoutineVerification.confirmFinalReview()` 한 곳뿐).

`AUTO_VALID`가 되어도 즉시 하트/포인트가 지급되지 않는다 — `countedInScore=true`로만 표시되고,
실제 집계/지급은 별도 이벤트 구독자(달성률 서비스, 아직 미구현)의 책임이다.

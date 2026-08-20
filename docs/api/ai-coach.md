# AI Coach API

## Local Preview

이 API는 Android 연동과 AI Coach 흐름 확인을 위한 **로컬 개발 전용 API**다. `local` Spring Profile에서만 활성화되며, 운영 API가 아니다.

```text
POST /api/v1/dev/ai-coach/preview
Content-Type: application/json
```

실행:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

### Request

```json
{
  "challengeName": "물 마시기",
  "todayScheduled": true,
  "todayCompleted": false,
  "todayVerificationPending": false,
  "completedCount": 3,
  "requiredCompletionCount": 5,
  "currentStreak": 2,
  "previousBestStreak": 2,
  "remainingOpportunityCount": 5,
  "pendingDecisionCount": 0,
  "groupCompletionRate": 0.8,
  "previousChallengeCompletionRate": null,
  "certificationDeadline": "2026-08-11T09:00:00Z",
  "challengeCompleted": false
}
```

| 필드 | 형식 | 필수 | 제약/의미 |
|---|---|---:|---|
| `challengeName` | String | O | 표시 이름, 공백 불가, 최대 100자 |
| `todayScheduled` | Boolean | O | 오늘이 인증 가능한 일정인지 나타내는 상위 정책의 계산 결과 |
| `todayCompleted` | Boolean | O | 오늘 인증 완료 여부 |
| `todayVerificationPending` | Boolean | O | 오늘 제출한 인증이 판정 대기 중인지 |
| `completedCount` | Integer | O | 현재까지 인정된 인증 수, 0 이상 |
| `requiredCompletionCount` | Integer | O | 완주에 필요한 인증 수, 1 이상 |
| `currentStreak` | Integer | O | 현재 연속 기록, 0 이상 |
| `previousBestStreak` | Integer | O | 현재 기록을 반영하기 전 최고 연속 기록, 0 이상 |
| `remainingOpportunityCount` | Integer | O | 남은 인증 기회 수, 0 이상 |
| `pendingDecisionCount` | Integer | O | 승인 결과가 확정되지 않은 판정 대기 수, 0 이상 |
| `groupCompletionRate` | Double | X | 그룹 진행률, `0.0`~`1.0` |
| `previousChallengeCompletionRate` | Double | X | 비교할 이전 챌린지 진행률, `0.0`~`1.0` |
| `certificationDeadline` | ISO-8601 Instant | X | 현재 인증 마감 시각 |
| `challengeCompleted` | Boolean | O | 챌린지 완료 여부 |

`todayScheduled`, `remainingOpportunityCount` 등 일정 관련 값은 Preview에서만 호출자가 입력한다. 아직 확정되지 않은 RoutineSchedule을 일일 수행으로 가정하지 않는다.

`todayVerificationPending=true`는 `todayScheduled=true`, `todayCompleted=false`, `pendingDecisionCount>=1`과 함께만 사용한다. Preview 계약에서 두 신규 필드는 명시적으로 필수다.

### Response

```json
{
  "title": "오늘 인증이 아직 남아 있어요",
  "message": "인증 마감까지 약 60분 남았어요.",
  "insightType": "DEADLINE_APPROACHING",
  "routineState": "ATTENTION",
  "actionType": "OPEN_CERTIFICATION",
  "actionLabel": "인증하기",
  "generationType": "TEMPLATE"
}
```

| 필드 | 의미 |
|---|---|
| `title` | Coach 제목 |
| `message` | Coach 본문 |
| `insightType` | Backend가 선택한 최우선 진행 Insight. Insight가 없을 때만 `null` |
| `routineState` | Backend가 계산한 식물 표현용 의미 상태 |
| `actionType` | Backend가 결정한 Android Navigation 의미 |
| `actionLabel` | CTA 문구. `actionType=NONE`이면 빈 문자열 `""` |
| `generationType` | `AI` 또는 `TEMPLATE` |

`routineState`, `actionType`, `generationType`은 항상 non-null이다. `generatedAt`은 현재 갱신/캐시 정책이 없어 제공하지 않는다.

## Enum Contract

### InsightType

- `VERIFICATION_PENDING`: 오늘 제출한 인증이 판정 대기 중임
- `TODAY_NOT_COMPLETED`: 오늘 예정된 인증이 아직 완료되지 않음
- `DEADLINE_APPROACHING`: 인증 마감 임박
- `STREAK_CONTINUING`: 연속 기록 유지 중
- `STREAK_RECORD`: `currentStreak`이 `previousBestStreak`을 갱신하고 3회 이상임
- `COMPLETION_RISK`: 남은 기회 대비 필요한 인증 수가 많음
- `GROUP_GOAL_NEAR`: 그룹 목표가 기준 진행률 이상임
- `IMPROVED_FROM_PREVIOUS`: 이전 챌린지보다 진행률이 개선됨

기본 우선순위는 `VERIFICATION_PENDING → DEADLINE_APPROACHING → COMPLETION_RISK → GROUP_GOAL_NEAR → STREAK_RECORD → STREAK_CONTINUING → IMPROVED_FROM_PREVIOUS → TODAY_NOT_COMPLETED`다. 오늘 판정 대기일 때는 Deadline/Today 미완료 Insight 자체를 생성하지 않는다.

### RoutineState

| Backend 값 | Android 표현 예시 |
|---|---|
| `GOOD` | 건강한 식물 |
| `ATTENTION` | 조금 처진 식물 |
| `AT_RISK` | 시든 식물 |
| `COMPLETED` | 꽃이 핀 식물 |

Backend는 상태만 반환한다. 이미지 URL, Asset 이름, Animation은 Android가 선택한다. Android는 진행률이나 마감 시간으로 상태를 다시 계산하지 않는다.

### ActionType

| Backend 값 | Android 동작 예시 |
|---|---|
| `OPEN_CERTIFICATION` | 인증 화면 이동 |
| `OPEN_GROUP` | 그룹 현황 이동 |
| `OPEN_PROGRESS` | 진행 현황 이동 |
| `NONE` | 이동 없음 |

Backend는 Compose route나 화면 클래스 이름을 반환하지 않는다. Android Navigation Router가 위 의미를 앱 내부 route로 매핑한다.

## HTTP Status와 오류

- 정상 AI 생성: `200 OK`, `generationType=AI`
- Provider 미설정, timeout, network/HTTP 오류, 응답 검증 실패: `200 OK`, `generationType=TEMPLATE`
- Request Validation 또는 JSON 형식 오류: `400 Bad Request`
- `local` Profile이 아닌 환경: Controller 미등록으로 endpoint 없음

Validation 오류 형식:

```json
{
  "code": "INVALID_REQUEST",
  "message": "요청값이 올바르지 않습니다."
}
```

## AI 호출 정책

- Backend가 Progress, Insight, RoutineState, ActionType을 계산한다.
- Completion Risk는 `remainingRequiredCount` 대비 `pendingDecisionCount + remainingOpportunityCount`를 최대 가능 용량으로 사용한다. 둘이 같으면 모든 판정과 남은 기회가 성공해야 하므로 `HIGH`다.
- 오늘 판정 대기에서는 `TODAY_NOT_COMPLETED`와 `DEADLINE_APPROACHING`을 생성하지 않고 `OPEN_PROGRESS`를 반환한다.
- AI Provider는 이미 계산된 최소 Context를 받아 `title`, `message`만 생성한다.
- Insight가 없거나 챌린지가 완료된 경우에는 AI를 호출하지 않고 Template을 반환한다.
- AI 실패는 API 500으로 전파하지 않는다.
- OpenAI 요청은 `store=false`로 Response 저장 비활성화를 요청한다. 조직 단위 데이터 보존 정책을 의미하지는 않는다.
- Request Body와 전체 CoachContext는 로그하지 않는다.

## Production API

Preview API는 DB Progress Domain과 분리된 개발용 경로라서 테스트 Fact를 직접 받는다. Android가 계산한 값을 신뢰하는 운영 구조가 아니다. 운영 API는 인증된 사용자와 현재 DB 사실만 사용한다.

```text
GET /api/v1/groups/{groupId}/ai-coach
POST /api/v1/groups/{groupId}/ai-coach/follow-up
```

GET 응답에는 `ACTIVE` 참여자에게만 다음 backend-owned `suggestedQuestions`가 포함된다.

```text
PACE_CHECK
NEXT_ACTION
GROUP_PROGRESS
```

Follow-up request는 `{ "questionId": "PACE_CHECK" }` 형식이며 free-form question을 받지 않는다. Backend가 id를 신뢰된 instruction으로 변환해 기존 Progress/Insight/Provider/Template pipeline에 넣는다. Provider 실패는 기존 GET과 마찬가지로 `generationType=TEMPLATE`인 200 응답으로 degrade한다. 대화나 질문 기록은 저장하지 않는다.

전체 production JSON과 status 계약은 [Android MVP API Contract](android-mvp-api-contract.md#ai-coach)를 따른다.

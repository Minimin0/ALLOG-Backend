# 비서AI 개발

## 1. 기존 프로젝트 분석 결과

- 위치: Backend repository 루트 (각자 clone 경로)
- 현재 기술 스택: 미정. `README.md`에 Spring Boot 등 서버 프레임워크는 팀 합의 후 초기화한다고 명시되어 있음.
- 현재 소스 구조: `src/README.md`만 존재하며 Entity, Controller, Service, Repository, DTO, Exception, Auth, Migration, Test 구조는 아직 없음.
- 환경 변수 방식: `.env.example` 기반.
- 외부 AI API 연결 코드: 없음.

## 2. 추가/수정한 파일 목록

- `ai 개발/비서 ai 개발/README.md`: 분석 및 작업 보고서
- `ai 개발/비서 ai 개발/ai-coach.mjs`: Progress Snapshot, Insight Engine, Priority, AI Provider, Template Fallback, Cache 포함 MVP 코드
- `ai 개발/비서 ai 개발/ai-coach.test.mjs`: Node 기본 테스트 러너 기반 테스트
- `.env.example`: AI Coach용 환경변수 예시 추가

## 3. 구현한 Architecture

현재 저장소가 프레임워크 초기화 전이라 Spring 프로젝트를 새로 만들지 않았다.

대신 다음 흐름을 의존성 없는 도메인 모듈로 구현했다.

사용자 수행 데이터
→ `createProgressSnapshot`
→ `detectProgressInsights`
→ `selectTopInsight`
→ `buildCoachContext`
→ `OpenAiCoachProvider`
→ 실패 시 `templateCoachResponse`
→ `AiCoachService.getCoachMessage`

## 4. ProgressSnapshot 구성

구성값:

- `userId`
- `participationId`
- `challengeId`
- `challengeName`
- `currentDay`
- `totalDays`
- `remainingDays`
- `todayCompleted`
- `completedCount`
- `requiredCompletionCount`
- `personalCompletionRate`
- `currentStreak`
- `bestStreak`
- `missedDays`
- `groupCompletionRate`
- `groupCompletedToday`
- `groupMemberCount`
- `certificationDeadline`
- `minutesUntilDeadline`
- `previousChallengeCompletionRate`
- `snapshotGeneratedAt`
- `remainingRequiredCount`
- `remainingAvailableDays`
- `completionRiskLevel`

## 5. Insight Engine 규칙

- `TODAY_NOT_COMPLETED`: 오늘 인증이 아직 완료되지 않음
- `DEADLINE_APPROACHING`: 오늘 미인증이고 마감까지 120분 이하
- `STREAK_CONTINUING`: 현재 연속 성공 3일 이상
- `STREAK_RECORD`: 현재 연속 성공이 이전 최고 기록 초과
- `COMPLETION_RISK`: 남은 일수 대비 필요한 인증 횟수가 빠듯함
- `GROUP_GOAL_NEAR`: 그룹 달성률 80% 이상
- `IMPROVED_FROM_PREVIOUS`: 현재 개인 달성률이 이전 챌린지 달성률보다 높음

정책값은 `DEFAULT_POLICY`에서 바꿀 수 있게 분리했다.

## 6. Insight Priority 규칙

우선순위:

1. `DEADLINE_APPROACHING`
2. `COMPLETION_RISK`
3. `GROUP_GOAL_NEAR`
4. `STREAK_RECORD`
5. `STREAK_CONTINUING`
6. `IMPROVED_FROM_PREVIOUS`
7. `TODAY_NOT_COMPLETED`

## 7. AI API 연결 구조

`OpenAiCoachProvider`가 OpenAI Responses API를 호출한다.

- API Key: `OPENAI_API_KEY`
- Model: `AI_COACH_MODEL`
- Endpoint: `https://api.openai.com/v1/responses`
- Structured Output: `text.format.type = json_schema`

AI에는 Raw Entity나 개인정보를 전달하지 않고 `CoachContext`만 전달한다.

## 8. Fallback 구조

다음 경우 Template Fallback을 반환한다.

- `OPENAI_API_KEY` 없음
- `AI_COACH_MODEL` 없음
- OpenAI API 장애
- 네트워크 오류
- AI 응답 JSON 파싱 실패
- AI 응답 Schema 검증 실패

Fallback도 `actionType`, `actionLabel`을 포함한다.

## 9. 추가한 API

아직 서버 프레임워크가 없으므로 실제 Controller는 추가하지 않았다.

추후 Spring Boot 초기화 후 권장 Endpoint:

`GET /api/participations/{participationId}/ai-coach`

응답 예:

```json
{
  "title": "오늘 인증이 아직 남아 있어요",
  "message": "마감까지 약 1시간 남았습니다.",
  "actionType": "OPEN_CERTIFICATION",
  "actionLabel": "인증하기",
  "generationType": "AI",
  "insightType": "DEADLINE_APPROACHING"
}
```

## 10. DB 변경사항

DB 테이블은 추가하지 않았다.

현재 저장소에는 Entity/Migration 구조가 없고, 동일 Insight 중복 호출 방지는 인메모리 `Map` Cache로 최소 구현했다.

추후 서버 구조가 정해지면 다음 테이블을 검토한다.

- `progress_insight`
- `ai_coach_message`

## 11. 환경변수/설정값

추가:

- `OPENAI_API_KEY`
- `AI_COACH_MODEL`

정책값:

- `deadlineApproachingMinutes`: 120
- `streakContinuingDays`: 3
- `completionRiskMediumRatio`: 0.7
- `groupGoalNearRate`: 0.8

## 12. 테스트 결과

실행 명령:

```bash
node --test "ai 개발/비서 ai 개발/ai-coach.test.mjs"
```

검증한 시나리오:

- 오늘 인증 완료 시 `DEADLINE_APPROACHING` 미발생
- 오늘 미인증 + 마감 60분 전 → `DEADLINE_APPROACHING`
- 3일 이상 연속 성공 → `STREAK_CONTINUING`
- 기존 최고 연속 기록 초과 → `STREAK_RECORD`
- 남은 일수와 필요한 인증 횟수 동일 → `COMPLETION_RISK HIGH`
- 그룹 목표 근접 → `GROUP_GOAL_NEAR`
- 동시 Insight 발생 시 우선순위 적용
- AI API 실패 시 Fallback
- AI Schema 오류 시 Fallback
- 동일 Insight 반복 요청 시 AI 재호출 방지
- AI Context 내 불필요한 사용자 식별정보 제외

## 13. 실제 사용자 시나리오 예시

오늘 미인증
→ 마감 60분 전
→ `DEADLINE_APPROACHING`
→ AI Coach 생성
→ "오늘 인증이 아직 남아 있어요."
→ `OPEN_CERTIFICATION`

## 14. 현재 구현에서 남아 있는 한계

- 실제 DB Repository 연동 없음
- 실제 Home Controller 연동 없음
- 인메모리 Cache라 서버 재시작 시 사라짐
- 그룹 목표 수치가 확정되지 않아 `groupCompletionRate >= 0.8` 기준만 적용

## 15. 다음 단계에서 개선해야 할 부분

- Spring Boot 또는 확정된 백엔드 스택 초기화
- Entity/Repository 생성 후 `createProgressSnapshot` 입력을 실제 DB 조회로 대체
- Home API 응답에 `aiCoach` 필드 추가
- 운영 DB 또는 Redis 기반 Coach Message Cache 적용
- AI 호출 로그를 민감정보 없이 저장

## 16. 실행 방법 및 필요한 설정

테스트:

```bash
node --test "ai 개발/비서 ai 개발/ai-coach.test.mjs"
```

OpenAI 호출을 사용하려면:

```bash
export OPENAI_API_KEY="..."
export AI_COACH_MODEL="..."
```

## 자체 검증

현재 구현은 프레임워크 없는 상태에서 다음 핵심 흐름을 만족한다.

사용자 행동 데이터
→ Progress 계산
→ Insight 판단
→ 우선순위 Insight 선택
→ AI Coach Context 생성
→ AI Provider 호출
→ 실패 시 Template Fallback
→ CTA 포함 응답 반환

서버 프레임워크와 DB가 추가되면 `AiCoachService`를 Controller/Home API에서 호출하면 된다.

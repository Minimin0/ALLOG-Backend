# Production AI Coach Application

이 문서는 STEP 6A-6의 Production Progress와 기존 AI Coach 연결 계약이다. REST/JWT/Security 계약은 포함하지 않는다.

## Transaction Boundary

```text
ProductionAiCoachApplicationService (NOT_SUPPORTED)
        │
        ├─ ProductionAiCoachQueryService (readOnly Transaction)
        │     ├─ groupId + currentUserId Membership
        │     ├─ RoutineSchedule
        │     ├─ participationStartedAt eligible members
        │     ├─ Verification batch query
        │     ├─ PersonalProgressCalculator
        │     └─ GroupProgressCalculator
        │
        └─ immutable ProductionAiCoachFacts 반환

DB Transaction END
↓
Membership branch / ProgressAnalysisInput mapping
↓
existing AiCoachApplicationService
↓
OpenAI or Template Fallback
```

Application Service는 Repository를 사용하지 않고, Query Service는 AI Provider를 사용하지 않는다. 별도 Spring Bean이므로 self-invocation에 의존하지 않는다. `NOT_SUPPORTED`는 상위 호출자가 트랜잭션을 열었더라도 외부 AI 대기 중 DB 트랜잭션이 유지되지 않도록 한다.

## Production Query

Target은 임의 `memberId`가 아니라 `groupId + currentUserId`로 조회한다. ACTIVE일 때만 Schedule과 전체 Progress를 조회한다. Verification은 `scheduleId + eligible memberIds` 한 번의 batch query로 읽고, `Map<MemberId, List<Verification>>`로 조립해 기존 `PersonalProgressCalculator`에 전달한다. Target 개인 결과는 같은 전체 계산 결과에서 재사용한다.

Eligible denominator는 현재 status가 아니라 다음 조건으로 고정한다.

```text
routine_group_id = ?
AND participation_started_at IS NOT NULL
```

따라서 시작 후 COMPLETED/FAILED/LEFT/REMOVED도 denominator에 남고, 시작 전 LEFT/REMOVED는 제외된다. 중복 Member ID, ACTIVE인데 시작 이력이 없는 Target, JOINED인데 시작 이력이 있는 Target, Schedule 부재는 데이터 불일치로 거부한다.

`ProductionAiCoachFacts`에는 challenge name, participation status, 계산된 개인/그룹 facts만 존재한다. RoutineGroup, GroupMember, RoutineSchedule, Verification Entity와 식별자는 트랜잭션 밖으로 전달하지 않는다.

## ACTIVE Mapping

| ProgressAnalysisInput | Source |
|---|---|
| todayScheduled | PersonalProgressFacts.todayScheduled |
| todayCompleted | PersonalProgressFacts.todayCompleted |
| todayVerificationPending | PersonalProgressFacts.todayVerificationPending |
| completedCount | PersonalProgressFacts.completedCount |
| requiredCompletionCount | PersonalProgressFacts.requiredCompletionCount |
| currentStreak | PersonalProgressFacts.currentStreak |
| previousBestStreak | PersonalProgressFacts.previousBestStreak |
| remainingOpportunityCount | PersonalProgressFacts.remainingOpportunityCount |
| pendingDecisionCount | PersonalProgressFacts.pendingDecisionCount |
| groupCompletionRate | GroupProgressFacts.groupCompletionRate |
| previousChallengeCompletionRate | null (History Domain 없음) |
| certificationDeadline | PersonalProgressFacts.certificationDeadline |
| challengeCompleted | false (ACTIVE만 AI Engine 진입) |

challenge name은 사용자가 참여한 그룹 표시명인 `RoutineGroup.name`이다.

## Membership Branch

| Status | 처리 | Action | RoutineState |
|---|---|---|---|
| JOINED | 시작 전 deterministic template | OPEN_GROUP | null |
| ACTIVE | 기존 AI Coach pipeline | Insight 기반 | 기존 Progress 상태 |
| COMPLETED | 완료 deterministic template | OPEN_PROGRESS | COMPLETED |
| FAILED | 비난 없는 종료 template | OPEN_PROGRESS | null |
| LEFT | access exception | - | - |
| REMOVED | access exception | - | - |

JOINED/COMPLETED/FAILED/LEFT/REMOVED에서는 기존 AI Coach engine과 Provider를 호출하지 않는다. FAILED를 `challengeCompleted=false`로 AI Engine에 전달하거나 인증 CTA를 만들지 않는다. AAC Benefit은 별도 기능이다.

## Privacy

Query에는 내부 ID가 사용되지만 `CoachContext`와 Provider request에는 `userId`, `groupId`, `groupMemberId`, JWT, 연락처, JPA Entity를 넣지 않는다. AI는 기존 계약대로 title/message만 생성하고 Insight, RoutineState, Action, Progress 숫자는 Backend가 결정한다.

# Personal Progress

이 문서는 STEP 6A-4B의 계산 계약이며 제품 정책 전체의 확정을 의미하지 않는다.

## Source of Truth

```text
RoutineSchedule
+ Verification.status == APPROVED
→ PersonalProgressFacts
```

Android가 보낸 진행률, 완료 횟수, Streak를 사용하지 않는다. `PersonalProgressQueryService`는 해당 회원·스케줄의 Verification 전체 상태를 한 번 조회하고, `PersonalProgressCalculator`는 Repository 없이 순수 계산만 한다.

## Opportunity Outcome

계산 내부에서 각 Scheduled Opportunity를 다음처럼 파생한다.

- `SUCCESS`: `APPROVED`
- `OPEN`: 마감 전이고 아직 제출·승인되지 않음
- `PENDING_DECISION`: 마감과 관계없이 `SUBMITTED`, `PROCESSING`, `REVIEW_REQUIRED`
- `FAILED`: 마감 후 성공이나 판정 대기가 아님
- `FUTURE`: 미래 예정 기회

`PENDING_DECISION`은 Streak를 끊지 않지만 사용자가 새로 수행할 기회가 아니므로 `remainingOpportunityCount`에는 넣지 않는다. 대신 `pendingDecisionCount`로 사실을 보존한다. 오늘 예정 기회가 판정 대기면 `todayVerificationPending=true`로 반환한다.

## Streak

Streak는 캘린더 일수가 아니라 정렬된 Scheduled Opportunity 수열을 기준으로 한다. `SUCCESS`는 현재 구간을 늘리고 `FAILED`는 현재 구간을 과거 최고값에 반영한 후 0으로 끊는다. 마감 전 `OPEN`과 판정 대기는 아직 실패가 아니므로 살아 있는 Streak를 유지한다. `previousBestStreak`는 현재 살아 있는 구간을 제외한 완료된 과거 구간의 최댓값이다.

## Data Integrity

다음은 조용히 무시하지 않고 `IllegalStateException`으로 거부한다.

- 대상 회원·스케줄에 속하지 않는 Verification
- 실제 예정일이 아닌 `scheduledDate`
- 동일 예정 기회의 중복 Verification
- 미래 예정 기회의 `APPROVED`
- `requiredCompletionCount` > 전체 Scheduled Opportunity 수

Calculator는 Verification·GroupMember 상태를 변경하지 않는다. UserProgress 테이블과 Migration도 생성하지 않는다.

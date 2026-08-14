# Group Progress and Participation Completion

이 문서는 STEP 6A-5의 읽기 전용 평가 계약이며, 참여자·그룹의 최종 DB 상태를 저장하는 정책이 아니다.

## Group Progress

`GroupProgressCalculator`는 호출자가 이미 선정한 eligible `PersonalProgressFacts` 목록만 집계한다. `JOINED`, `ACTIVE`, `LEFT`, `REMOVED` 등을 보고 자동 필터링하지 않는다.

```text
completedRequirementCount
= min(member.completedCount, member.requiredCompletionCount)

totalRequiredCount
= member.requiredCompletionCount

groupCompletionRate
= completedRequirementCount / totalRequiredCount
```

필요 횟수를 초과한 개인 수행은 개인 목표까지만 그룹 진행에 기여하므로 결과는 `0.0`부터 `1.0`사이다. 합계는 `long`과 `Math.addExact`를 사용한다. eligible 회원이 0명이면 계산 불가로 거부한다.

`goalAchievedMemberCount`는 `completedCount >= requiredCompletionCount`인 회원 수이다. 저장된 `GroupMemberStatus.COMPLETED`의 수가 아니다. `pendingDecisionCount`는 eligible 회원의 판정 대기 전체 합계다.

## Participation Completion Evaluation

`goalAchieved`는 현재 요구 횟수를 채웠다는 사실이고, `finalizationReady`는 공식 종료 Transaction을 시작할 수 있다는 평가다.

```text
goalAchieved
= completedCount >= requiredCompletionCount

scheduleEnded
= now >= finalScheduledDeadline

finalizationReady
= scheduleEnded && pendingDecisionCount == 0
```

Finalization을 준비할 수 있을 때만 `recommendedOutcome`이 존재한다.

- `goalAchieved=true` → `COMPLETED`
- `goalAchieved=false` → `FAILED`
- Schedule 진행 중 또는 Pending 존재 → 결과 없음

## Final Scheduled Deadline

Schedule 종료는 `endDate` 기준이 아니다. `RoutineScheduleCalculator.scheduledDates()`의 마지막 실제 예정일을 `deadlineFor()`로 변환한 값이다.

```text
2026-08-10 ~ 2026-08-16
MON/WED/FRI
→ 마지막 예정일 2026-08-14
→ 2026-08-14 + deadlineTime + timezone
```

실제 예정일이 0개인 Schedule은 완료로 간주하지 않고 Domain inconsistency로 거부한다. 마감과 동일한 시각부터 Schedule은 종료된다.

## Why Evaluation Only

이 단계는 `GroupMember`, `RoutineGroup`, Verification을 변경하지 않는다. 조기에 `COMPLETED`를 저장하면 후속 `INVALIDATED`로 인해 성공 횟수가 줄었을 때 저장 상태를 되돌려야 한다.

최종 종료는 향후 다음 작업이 준비된 후 하나의 Orchestration Transaction으로 구성한다.

```text
Final Verification
→ Participation Results
→ Score / Ranking
→ Heart / Reward
→ RoutineGroup Completion
```

Membership denominator 정책이 미확정이므로 Group Query Service를 만들지 않았다. 별도 `GroupFinalizationEvaluation`도 현재는 Group Progress와 개인 Evaluation 목록에서 동일 값을 중복하므로 최종 Orchestrator가 생길 때 추가한다.

# Group Progress and Participation Completion

이 문서는 Group Progress의 읽기 전용 계산과 M3-L에서 영속화된 참여자·그룹 최종화 계약을 설명한다.

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

필요 횟수를 초과한 개인 수행은 개인 목표까지만 그룹 진행에 기여하므로 결과는 `0.0`부터 `1.0` 사이다. 합계는 `long`과 `Math.addExact`를 사용한다. eligible 회원이 0명이면 계산 불가로 거부한다.

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

| 평가 결과 | 최종 상태 |
| --- | --- |
| `goalAchieved=true` | `COMPLETED` |
| `goalAchieved=false` | `FAILED` |
| Schedule 진행 중 또는 pending decision 존재 | 결과를 저장하지 않음 |

## Final Scheduled Deadline

Schedule 종료는 `endDate` 기준이 아니다. `RoutineScheduleCalculator.scheduledDates()`의 마지막 실제 예정일을 `deadlineFor()`로 변환한 값이다.

```text
2026-08-10 ~ 2026-08-16
MON/WED/FRI
→ 마지막 예정일 2026-08-14
→ 2026-08-14 + deadlineTime + timezone
```

실제 예정일이 0개인 Schedule은 완료로 간주하지 않고 Domain inconsistency로 거부한다. 마감과 동일한 시각부터 Schedule은 종료된다.

## Persisted Group Finalization

`GroupFinalizationService`는 lifecycle reconciler가 이미 `PESSIMISTIC_WRITE`로 잠근 `ACTIVE` `RoutineGroup`에 대해서만 동작한다. 공식 참여자는 현재 status가 아니라 `participationStartedAt IS NOT NULL`로 선정한다. 각 참여자의 verification을 배치 조회하고 기존 `PersonalProgressCalculator` 및 `ParticipationCompletionEvaluator`를 재사용한다.

모든 참여자가 `finalizationReady`가 된 후에만 outcome을 먼저 전부 계산하고, 그 다음에야 `ACTIVE → COMPLETED` 또는 `ACTIVE → FAILED`를 기록한다. 하나라도 Schedule 종료 전이거나 pending decision이 있으면 어떤 row도 변경하지 않는다. 따라서 partial finalization은 발생하지 않는다.

그룹은 모든 공식 참여자의 outcome이 저장된 뒤 `ACTIVE → COMPLETED`가 된다. `RoutineGroup.COMPLETED`는 그룹 lifecycle이 종료됐다는 뜻이며 전원이 성공했다는 뜻은 아니다. 같은 그룹 안에서 `COMPLETED`와 `FAILED` outcome이 섞이는 것은 정상이다.

## Background Reconciliation과 자산 경계

scheduler는 candidate ID 검색과 group별 delegation만 수행하고, 각 group은 별도 transaction에서 schedule-authoritative expiry, FULL activation, 또는 finalization을 처리한다. terminal 상태의 재처리는 no-op이며 읽기 endpoint는 lifecycle을 변경하지 않는다.

M3-L finalization은 Heart와 Reward를 변경하지 않는다. Heart spend/refund, Reward policy, ranking, `successfulRoutines` 노출은 후속 milestone의 별도 product·domain 계약이다.

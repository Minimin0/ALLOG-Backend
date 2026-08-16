# Membership Lifecycle and Group Activation

이 문서는 M3-L에서 영속화된 그룹 참여 이력, 시작, 시작 전 이탈·취소·만료 계약을 설명한다. Heart 차감·환불은 의도적으로 M3-C까지 연결하지 않는다.

## Current Status와 공식 참여 이력

`GroupMember.status`는 현재 상태이고, `participationStartedAt`은 공식 챌린지에 참여자로 시작한 역사적 사실이다.

```text
participationStartedAt == null
→ 공식 참여자로 시작한 적 없음
→ JOINED / LEFT / REMOVED만 시작 전 상태로 가능

participationStartedAt != null
→ fixed denominator 대상
→ ACTIVE에서 최종적으로 COMPLETED 또는 FAILED가 됨
```

Eligibility는 `participationStartedAt IS NOT NULL`로 조회하며 현재 status로 자동 제외하지 않는다. 시작 전 `LEFT`와 `REMOVED`에는 시작 시각이 없으므로 최종화 대상이 아니다. M3-L은 시작 후 자발 이탈 정책을 만들지 않으며, `ACTIVE → LEFT` 또는 `ACTIVE → REMOVED` 전이는 지원하지 않는다.

## FULL-only Atomic Group Activation

`RoutineGroupActivationService.activate(groupId, clock)`는 아래 순서를 하나의 트랜잭션에서 수행한다. 생산 환경의 시작은 `FULL → ACTIVE`만 허용하며, `RECRUITING → ACTIVE` 직접 전이는 허용하지 않는다.

```text
1. RoutineGroup.id row PESSIMISTIC_WRITE lock
2. FULL 상태, 시작 전 멤버 상태, 정원 충족을 검증
3. RoutineSchedule의 남은 참여 가능 횟수를 검증
4. Clock을 한 번 읽어 activationTime 생성
5. 모든 JOINED 회원을 ACTIVE로 전환하고 같은 participationStartedAt 기록
6. RoutineGroup을 ACTIVE로 전환
7. commit
```

Creator는 생성 시점부터 `JOINED`이며 정원 한 자리를 차지한다. 마지막 빈자리를 채운 Join은 같은 그룹 락 및 같은 트랜잭션에서 `RECRUITING → FULL → ACTIVE`를 완료한다. `maxMembers == 1`인 그룹도 생성 트랜잭션 안에서 동일한 전이를 완료한다. `RoutineGroup.startedAt`은 denominator 재현에 필요하지 않아 추가하지 않았다.

## Join, Leave, Cancel과 Lock 순서

`canAcceptNewMember()`는 `RECRUITING`에서만 true다. Join, activation, leave, cancel, expiry, completion/reconciliation은 모두 `RoutineGroup` row를 공통 직렬화 지점으로 사용한다. 표준 순서는 `RoutineGroup PESSIMISTIC_WRITE lock → GroupMember 조회/변경`이며 역순 획득은 금지한다.

시작 전 자발 이탈은 non-owner `MEMBER`의 `JOINED` 상태에서만 가능하고, 해당 회원은 `LEFT`가 된다. 이미 `LEFT`인 동일 요청은 멱등적인 no-op이며 membership row는 삭제하지 않는다. `FULL` 상태에서의 이탈은 그룹을 다시 `RECRUITING`으로 연다. Owner는 leave하지 않고 cancel을 사용한다.

Owner만 `RECRUITING` 또는 `FULL` 그룹을 취소할 수 있다. 취소 시 그룹은 `CANCELLED`, 남아 있는 `JOINED` 회원은 `REMOVED`가 되며 이미 `LEFT`인 회원은 유지된다. 이미 `CANCELLED`인 owner 재시도는 멱등적인 no-op이다.

## Schedule-authoritative Expiry와 Heart 경계

임의 모집 마감 시간은 없다. `RoutineScheduleCalculator.participationEligibleScheduledDates(schedule, now).size()`가 `requiredCompletionCount`보다 작아져 지금 시작해도 목표 달성이 불가능하면, 시작 전 `RECRUITING` 또는 `FULL` 그룹은 `EXPIRED`가 된다. 남은 `JOINED` 회원은 `REMOVED`가 되고 `LEFT`는 유지된다.

주기 실행기는 terminal state가 아닌 그룹 ID만 페이지로 찾고, 그룹별로 reconciler에 위임한다. 각 reconcile은 독립 트랜잭션과 그룹 row lock을 사용하므로 읽기 endpoint에 부수 효과가 없고, 여러 인스턴스가 같은 그룹을 처리해도 두 번째 실행은 갱신된 상태를 다시 읽어 no-op이 된다. scheduler는 기본적으로 비활성화되어 테스트를 비결정적으로 만들지 않는다.

M3-L은 Heart production caller를 추가하거나 호출하지 않는다. successful join에 대한 spend와 eligible leave/cancel/expiry에 대한 original debit refund는 M3-C의 별도 계약이다.

# Membership Lifecycle and Group Activation

이 문서는 STEP 6A-5.6의 공식 참여 이력과 그룹 Activation 트랜잭션 계약이다. 아직 확정되지 않은 최소 인원·중도 이탈 제품 정책은 포함하지 않는다.

## Current Status와 공식 참여 이력

`GroupMember.status`는 현재 상태이고, `participationStartedAt`은 공식 챌린지에 참여자로 시작한 역사적 사실이다.

```text
participationStartedAt == null
→ 공식 참여자로 시작한 적 없음

participationStartedAt != null
→ fixed denominator 대상
→ 이후 COMPLETED / FAILED / LEFT / REMOVED가 되어도 유지
```

Eligibility는 `participationStartedAt IS NOT NULL`로 조회하며 현재 status로 자동 제외하지 않는다. 반면 API Authorization은 현재 상태를 별도로 검사한다. 따라서 시작 후 `LEFT`/`REMOVED` 회원은 denominator에는 남지만 AI Coach 접근은 허용하지 않는 것이 후속 Application 계층의 계약이다.

## Atomic Group Activation

`RoutineGroupActivationService.activate(groupId, clock)`는 다음 순서를 하나의 트랜잭션에서 수행한다.

```text
1. RoutineGroup.id row PESSIMISTIC_WRITE lock
2. RECRUITING/FULL 상태 확인
3. GroupMember 전체 조회 및 일관성 검증
4. Clock을 한 번 읽어 activationTime 생성
5. 모든 JOINED 회원을 ACTIVE로 전환하고 같은 participationStartedAt 기록
6. RoutineGroup을 ACTIVE로 전환
7. commit
```

JOINED 회원이 없거나, 시작 전 그룹에 `ACTIVE`/`COMPLETED`/`FAILED` 회원 또는 이미 `participationStartedAt`이 있는 회원이 존재하면 전체 트랜잭션이 실패한다. 시작 전 `LEFT`/`REMOVED`이며 이력이 없는 회원은 변경하지 않는다. OWNER도 JOINED라면 다른 회원과 동일하게 공식 참여를 시작한다.

Activation은 `RECRUITING`과 `FULL`에서 허용한다. 이는 정원 미달 시작이 확정됐다는 뜻이 아니며, 후속 Application Policy가 더 제한할 수 있다. `RoutineGroup.startedAt`은 denominator 재현에 필요하지 않아 추가하지 않았다.

## Join과 Lock 순서

`canAcceptNewMember()`는 현재 MVP 계약상 `RECRUITING`에서만 true다. Domain guard만으로 Activation과 Join 경쟁을 막을 수 없으므로, 실제 Join Use Case도 다음 순서를 지켜야 한다.

```text
RoutineGroup row lock
→ status/capacity 확인
→ GroupMember insert
```

같은 그룹의 Activation, Join, 향후 Lifecycle 변경은 모두 `RoutineGroup` row를 공통 직렬화 지점으로 사용한다. 교착 방지를 위한 표준 lock 순서는 `RoutineGroup lock → GroupMember 조회/변경`이며 역순으로 획득하지 않는다. 현재 Join Service가 없으므로 실제 Activation-vs-Join 경쟁 검증은 후속 구현 책임이다.

## Timestamp 기술 부채

`participationStartedAt`은 기존 event/audit mapping과 동일하게 Java `LocalDateTime`, DB `TIMESTAMP(6)`을 사용한다. `Clock`으로 테스트 가능하고 한 Activation 내 같은 값은 보장하지만, 운영 서버/JDBC/MySQL timezone 기준은 아직 확정되지 않았다. 이번 단계에서는 `BaseTimeEntity`나 기존 timestamp 전체를 변경하지 않는다.

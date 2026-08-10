# Core Routine/Group Schema

이 문서는 STEP 6A-2의 MVP Backend 구현 계약이다. 제품 기획 확정을 의미하지 않는다.

## 책임

- `User`: 현재는 FK 연결을 위한 사용자 식별자만 소유한다.
- `RoutineDefinition`: 무엇을 수행하는지 정의한다.
- `RoutineGroup`: 어떤 Routine을 어떤 그룹 정책으로 함께 수행하는지 정의한다.
- `GroupMember`: User와 RoutineGroup 사이의 참여 관계, 현재 상태, 공식 참여 시작 이력을 표현한다.
- `RoutineSchedule`: 그룹 Routine을 언제 수행할 수 있는지 저장한다.
- AI Coaching은 이 Domain을 소유하지 않고 향후 Progress 결과만 소비한다.

## 관계

```text
User
 ├──< RoutineGroup.createdBy
 └──< GroupMember >── RoutineGroup >── RoutineDefinition
                              │
                              └── RoutineSchedule
                                      └──< specificDays
```

- `RoutineGroup : RoutineSchedule`은 1:1이다.
- 관계는 모두 단방향이며 To-One Fetch는 LAZY다.
- `User : RoutineGroup`을 `ManyToMany`로 매핑하지 않는다.
- `RoutineScheduleDay`는 독립 Entity가 아니라 `Set<DayOfWeek>` Element Collection이다.
- Element Collection 외에는 Cascade를 사용하지 않는다.

## Enum

모든 Enum은 DB `VARCHAR`와 JPA `EnumType.STRING`으로 저장한다.

```text
ScheduleType
- DAILY
- SPECIFIC_DAYS

GroupVisibility
- PUBLIC
- PRIVATE

RoutineGroupStatus
- DRAFT
- RECRUITING
- FULL
- ACTIVE
- COMPLETED
- CANCELLED
- EXPIRED

GroupMemberRole
- OWNER
- MEMBER

GroupMemberStatus
- JOINED
- ACTIVE
- COMPLETED
- FAILED
- LEFT
- REMOVED
```

현재는 그룹 공식 Activation에 필요한 `RoutineGroup.activate()`와 `GroupMember.startParticipation()`만 구현한다. 그 밖의 완료·실패·이탈 전이는 후속 작업이다.

## Schedule 불변식

- `startDate <= endDate`
- `deadlineTime`은 필수지만 기본값은 Entity가 결정하지 않는다.
- `timezone`은 유효한 Java `ZoneId` 문자열이어야 한다.
- `DAILY`의 `specificDays`는 비어 있어야 한다.
- `SPECIFIC_DAYS`에는 최소 한 요일이 필요하다.
- `WEEKLY_N`, `CUSTOM`, Schedule Calculator는 아직 지원하지 않는다.

## DB 제약

```text
routine_group.max_members > 0
routine_group.required_completion_count > 0
routine_schedule.start_date <= routine_schedule.end_date

UNIQUE routine_schedule(routine_group_id)
UNIQUE routine_schedule_day(schedule_id, day_of_week)
UNIQUE group_member(routine_group_id, user_id)

INDEX group_member(user_id, status)
INDEX group_member(routine_group_id, participation_started_at)
```

`group_member.participation_started_at TIMESTAMP(6) NULL`은 현재 status와 별개인 공식 참여 이력이다. `IS NOT NULL`인 회원은 이후 LEFT/REMOVED가 되어도 fixed denominator 후보로 남는다. Flyway V3는 기존 row를 추론해 backfill하지 않고 NULL로 둔다.

Cross-table 규칙인 `requiredCompletionCount <= totalScheduledOpportunityCount`는 Schedule 생성/활성화 Service에서 검증한다.

## Schema 관리

- Flyway Migration이 Schema의 Source of Truth다.
- Hibernate는 모든 Profile에서 `ddl-auto=validate`만 사용한다.
- 기본 Profile은 환경변수 기반 MySQL을 사용한다.
- `local`, `test` Profile은 H2 MySQL compatibility mode를 사용한다.
- H2 통과가 MySQL 호환성을 완전히 보장하지는 않는다.

생성/수정 시각은 별도 Auditing 설정 없이 Hibernate `@CreationTimestamp`, `@UpdateTimestamp`로 기록한다. 현재 JPA Provider가 Hibernate로 고정되어 있고, 단순 timestamp 두 개를 위해 Spring Data Auditing 설정을 추가하지 않기 위한 선택이다.

## STEP 6A-1 대비 실제 구현

- `RoutineScheduleDay`는 예상대로 물리 테이블이지만 별도 Java Entity는 아니다.
- `requiredCompletionCount`는 `RoutineGroup`이 소유한다.
- STEP 6A-2에서는 `RoutineOccurrence`, `Verification`, `UserProgress`를 만들지 않았다. 이후 Verification은 Flyway V2에서 추가됐다.
- 실제 후속 조회에 필요한 `GroupMemberRepository`만 만들었다.
- Audit/Join/공식 참여 시작 시각은 DB `TIMESTAMP(6)`, Java `LocalDateTime`으로 매핑했다. 서버/JDBC/DB 시간대 운영 기준은 배포 정책에서 확정해야 한다.

## Production AI 연결 TODO

Production Progress Adapter에서 `GroupMemberStatus.ACTIVE/COMPLETED/FAILED`를 Progress Fact로 변환한다. AI Domain은 `GroupMemberStatus`를 직접 import하지 않는다.

Schedule Calculator와 Verification 기반 개인 Progress Calculator가 준비되기 전에는 Production AI Coach Source를 연결하지 않는다.

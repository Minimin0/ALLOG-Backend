# Verification Domain

이 문서는 STEP 6A-4A의 MVP Backend 구현 계약이다. 제품 기획 확정을 의미하지 않는다.

## 책임

Verification은 특정 `GroupMember`가 특정 `RoutineSchedule`의 `scheduledDate`에 대해 가진 현재 공식 인증 Aggregate다.

```text
GroupMember
+ RoutineSchedule
+ scheduledDate
→ Verification
```

업로드한 날짜나 Android가 주장한 성공 여부를 Progress Source로 사용하지 않는다. Progress에서 성공으로 인정되는 상태는 `APPROVED` 하나뿐이다.

## 상태

- `PENDING_UPLOAD`: 인증 Aggregate 생성, 제출 전
- `SUBMITTED`: 미디어 제출 완료
- `PROCESSING`: Backend 또는 AI 판정 중
- `APPROVED`: 정상 인증으로 인정
- `REVIEW_REQUIRED`: 추가 운영 검토 필요
- `RETRY_REQUIRED`: 사용자 재제출 필요
- `REJECTED`: 최초 판정에서 인증 실패
- `INVALIDATED`: 과거 승인 후 무효화

## 상태 전이

| From | To | 허용 |
|---|---|---:|
| `PENDING_UPLOAD` | `SUBMITTED` | O |
| `SUBMITTED` | `PROCESSING` | O |
| `PROCESSING` | `APPROVED` | O |
| `PROCESSING` | `REVIEW_REQUIRED` | O |
| `PROCESSING` | `RETRY_REQUIRED` | O |
| `PROCESSING` | `REJECTED` | O |
| `REVIEW_REQUIRED` | `APPROVED` | O |
| `REVIEW_REQUIRED` | `RETRY_REQUIRED` | O |
| `REVIEW_REQUIRED` | `REJECTED` | O |
| `RETRY_REQUIRED` | `SUBMITTED` | O |
| `APPROVED` | `INVALIDATED` | O |

표에 없는 전이는 모두 거부한다. `INVALIDATED → SUBMITTED/APPROVED`는 재인증 정책이 확정되기 전까지 허용하지 않는다.

## Retry와 이력

MVP에서는 예정 기회당 Verification Aggregate 한 행을 유지한다. 재제출 시 같은 행을 `RETRY_REQUIRED → SUBMITTED`로 변경하고 `submittedAt`을 갱신한다.

```text
UNIQUE(group_member_id, routine_schedule_id, scheduled_date)
```

현재 구조는 나중에 `VerificationAttempt`가 Aggregate를 FK로 참조하는 확장을 막지 않는다. Attempt 수, 미디어 URL, S3 Key를 Verification에 미리 추가하지 않았다.

## Timestamp

- `createdAt`, `updatedAt`: 공통 Audit 시각
- `submittedAt`: 가장 최근 제출 시각
- `approvedAt`: 승인 시각
- `invalidatedAt`: 승인 무효화 시각

기존 프로젝트와 일치하도록 `LocalDateTime`을 사용한다. 운영 Clock/timezone 기준은 별도 배포 정책에서 확정해야 한다. 모호한 의미의 `processedAt`은 추가하지 않았다.

## 생성 검증

`VerificationCreator`는 다음을 검증한다.

1. GroupMember와 RoutineSchedule이 같은 RoutineGroup에 속함
2. `scheduledDate`가 Schedule Calculator 기준 실제 예정일임
3. 동일 Aggregate가 Application 조회 시점에 존재하지 않음

동시 요청은 Application의 exists 검사만으로 막을 수 없으므로 DB Unique를 최종 방어선으로 사용한다. GroupMember 상태별 인증 허용과 Deadline 이후 제출 정책은 Upload/Submit Use Case에서 결정한다.

## Media와 판정

이번 단계에는 다음을 포함하지 않는다.

- VerificationMedia
- S3/Presigned URL
- Vision AI
- 운영자 Review API
- Progress 재계산

향후 `VerificationMedia`와 `VerificationAttempt`는 Verification Aggregate를 참조하도록 확장한다. `INVALIDATED` 발생 시 Progress/Ranking/Reward 재계산 트리거도 별도 단계에서 구현한다.

# Verification Template, Criteria, and Observation Contract

이 문서는 STEP 6A-9E-I-RESUME-B의 Production 계약이다.

## Authority

```text
User-created Routine
→ RoutineGroup
→ VerificationTemplateKey + exact Criteria Reference
→ Backend-owned VerificationCriteria
→ Media
→ AI Provider
→ Structured Observations only
```

AI Provider는 Criteria, recommendation, `VerificationStatus`, Reward, Progress를 결정하지 않는다. Backend
`DecisionPolicy`, threshold, Production Provider와 Scheduler도 아직 활성화하지 않는다.

## Product identity separation

- `RoutineDefinition`: 사용자가 수행하는 경험이다.
- `VerificationTemplate`: ALLOG가 승인한 인증 의미다.
- `VerificationCriteria`: 그 의미의 immutable versioned 기준이다.
- `VerificationAnalysis`: 한 번의 분석과 enqueue 시점의 exact Criteria provenance다.

`routine_definition.routine_key`는 nullable legacy Product identity로 유지하지만 Verification binding에는 사용하지
않는다. 이름, description, DB ID도 Template/Criteria identity로 사용하지 않는다.

## Pilot template and criteria

현재 code-owned catalog entry는 한 건이다.

| Field | Value |
|---|---|
| Template key | `MEAL_PHOTO_RECORD` |
| Display name | `식사 사진 기록` |
| Exact Criteria reference | `meal-photo-record@1` |
| Supported modality | `PHOTO` only |

Pilot의 목적은 사진에 평가 가능한 식사 또는 음식 장면이 있는지를 관찰하는 것이다. 실제 섭취, 건강성, 영양,
양, 소유자, 신원, 촬영/마감 시각, 과거 재사용은 추론하지 않는다. 별도 category taxonomy는 현재 Backend 실행에
사용되지 않아 추가하지 않았다.

## Code-owned catalog

Catalog는 다음 explicit lookup만 제공한다.

- `VerificationTemplateKey → VerificationTemplate`
- `Criteria.Reference → VerificationCriteria`
- `(TemplateKey, exact Reference) → VerificationCriteria`

`latest`, `newest`, version sorting, DB catalog와 plugin registry는 없다. 같은 criteria ID의 v2가 배포되어도 기존
Group과 Analysis는 자동으로 바뀌지 않는다.

## Group binding

`RoutineGroup`은 nullable pair를 소유한다.

- `verification_template_key VARCHAR(64)`
- `verification_criteria_reference VARCHAR(64)`

AI 인증형 Group은 둘 다 non-null이고 legacy/기록형 Group은 둘 다 null이다. 한쪽만 설정된 상태는 V8 DB CHECK와
Domain accessor invariant가 거부한다. Binding은 생성자에서만 설정되며 setter/update operation이 없어 현재
lifecycle 전체에서 immutable하다. 기존 row는 backfill하지 않는다.

## Submit and provenance

```text
lock and revalidate
→ read persisted Group binding
→ exact catalog resolve and integrity validation
→ validate Criteria modality
→ media confirm
→ Verification SUBMITTED
→ VerificationAnalysis PENDING with exact reference
```

모든 변경은 동일 DB transaction이다. Unknown Template, unknown Criteria 또는 mismatch이면 media confirmation,
Verification submit과 Analysis enqueue가 함께 rollback된다. Legacy Group은 nullable Analysis를 계속 생성하지만
InputLoader가 missing Criteria로 Provider processing을 차단한다.

Analysis에 저장된 reference가 Worker의 authoritative provenance다. InputLoader가 이를 canonical form으로 파싱하고
MediaProcessor가 code-owned catalog에서 exact resolve한다. Worker는 현재 Group binding을 다시 조회하거나 latest를
선택하지 않으므로 retry/recovery/reclaim에서도 동일 기준을 사용한다. ResultService도 저장된 reference와 결과의
expected reference가 일치할 때만 결과를 반영한다.

## Observation and privacy boundary

기존 observation field 의미는 유지한다.

- `objectPresence`: Criteria target evidence 관찰 가능 여부
- `relevanceScore`: Criteria 관찰 관련도 `0..1`; 합격 확률이나 threshold가 아님
- `anomalyDetected`: 시각적 이상 징후 관찰; reuse 판정이 아님
- `framedProperly`: evidence 평가 가능 framing 여부
- bounded `reasonCode`: observation 완전성/불확실성 표현

Provider boundary에는 TemplateKey, Analysis/Verification/User/Group ID, storage object key나 upload URL을 보내지 않는다. Criteria의
provider-neutral evidence requirement, modality, normalized Content-Type과 media bytes만 전달한다.

## MVP DecisionPolicy Product Contract

STEP 6A-9E-J.5에서 확정한 Product 계약이다. **아직 구현하지 않았다.** Java class, bean, Processor
wiring, Provider/Worker activation은 전부 다음 STEP이다.

### 근거

J.4 / J.4-P / J.4b의 실제 Anthropic 호출 결과(`baselines/` 3개 artifact)에서 신뢰 가능하다고 확인된
signal은 둘뿐이다.

| Signal | 상태 |
|---|---|
| `objectPresence` | Human Label과 7/7 일치. **Decision 입력** |
| `anomalyDetected` | 화면 재촬영 1건 탐지, normal 3건 false anomaly 0. **Decision 입력** |
| `relevanceScore` | present 0.97~1.0 / absent 0.0의 이분 분포. `objectPresence` 파생. **Decision 입력 아님** |
| `framedProperly` | 7/7에서 `objectPresence`와 동조. J.4b calibration 실패. **Decision 입력 아님** |
| `reasonCode` | observation 완전성 표현. anomaly 외 분기 없음. **Decision 입력 아님** |

### Truth table

Recommendation은 두 boolean만으로 결정한다. 이 표가 authority다.

| `anomalyDetected` | `objectPresence` | Recommendation |
|---|---|---|
| true | true | `REVIEW_REQUIRED` |
| true | false | `REVIEW_REQUIRED` |
| false | false | `REJECT_CANDIDATE` |
| false | true | `PASS` |

Anomaly가 우선한다. `anomalyDetected=true`이면 `objectPresence` 값과 무관하게
`REVIEW_REQUIRED`다. 같은 row에서 `relevanceScore`, `framedProperly`, `reasonCode`가 무엇이든
Recommendation은 바뀌지 않는다.

**Selected Threshold: NONE.** `relevanceScore`에 어떤 numeric gate도 두지 않는다.

동일 Observation은 항상 동일 Recommendation을 낳는다. 시각, model 이름, 사용자, Group, reward에
의존하지 않는다.

### PASS의 의미와 비의미

PASS는 "criteria가 요구한 visual target이 보였고 concrete integrity anomaly가 관찰되지 않았다"만
뜻한다. 실제 섭취, 건강성, 영양, 양, 소유자, 신원, 촬영 시각, 재사용 없음의 증명, identity
verification 중 **어느 것도 의미하지 않는다.**

### PARTIAL trade-off

MVP AI는 부분 증거를 안정적으로 분리하지 못한다(J.4b에서 prompt calibration 1회 실증 실패). 따라서
Human Label이 `PARTIAL_OR_AMBIGUOUS_EVIDENCE`인 사진도 `objectPresence=true` /
`anomalyDetected=false`이면 PASS가 된다. 이는 결함을 감춘 것이 아니라 **false reject를 줄이기 위한
명시적 Product trade-off**이며, Quality Hardening에서 재검토한다.

### REJECT_CANDIDATE / REVIEW_REQUIRED의 의미

`REJECT_CANDIDATE`는 최종 거절이 아니다. AI가 요구된 visual target을 관찰하지 못했으므로 자동
PASS하지 않고 guided retry로 유도해야 하는 상태다. 즉시 페널티, 하트 몰수, 리워드 박탈, 부정행위
확정, 자동 제재는 이 계약에 포함되지 않는다.

`REVIEW_REQUIRED`는 부정행위 확정이 아니다. concrete integrity anomaly가 관찰되어 자동 PASS하지
않는다는 뜻이다. 사용자 대상 문구는 단정적 표현("부정행위가 감지되었습니다")이 아니라 "인증 확인이
추가로 필요합니다" 방향으로 정한다. 실제 copy는 별도 UX 작업이다.

### 적용 범위

DecisionPolicy는 **Provider success observation에만** 적용한다.

- Provider/system failure(`AUTHENTICATION`, `RATE_LIMITED`, `PROVIDER_5XX`, `TIMEOUT`, `NETWORK`,
  `INVALID_RESPONSE`, `INTERRUPTED`)는 Recommendation을 만들지 않는다. **System failure는
  Verification failure가 아니다.** 기존 실패 경로(`VerificationAnalysis.fail`)를 그대로 쓴다.
- 계약을 위반한 Observation도 Recommendation으로 억지 mapping하지 않는다. 5-field required schema와
  `MediaProcessor`의 required-observation 검사가 이미 `INVALID_RESPONSE`로 처리한다.
- 기록형(record-only) Group은 Criteria binding이 없어 `VerificationAnalysisInputLoader`가 이미
  차단하므로 DecisionPolicy에 도달하지 않는다.

### Authority

Provider는 Observation만 반환한다. Recommendation은 Backend가 결정한다. Provider prompt에
Recommendation 규칙을 넣지 않는다.

Recommendation은 `VerificationStatus`가 **아니다.** Recommendation → Verification 최종 상태 전이,
Heart/Reward/Ranking/Progress 반영은 이 계약에 포함되지 않으며 별도 Product Contract다.

### 구현 시 test matrix

| # | 입력 | 기대 |
|---|---|---|
| 1 | anomaly=true, presence=true | `REVIEW_REQUIRED` |
| 2 | anomaly=true, presence=false | `REVIEW_REQUIRED` |
| 3 | anomaly=false, presence=false | `REJECT_CANDIDATE` |
| 4 | anomaly=false, presence=true | `PASS` |
| 5 | Provider failure | DecisionPolicy 미호출, Recommendation 없음 |
| 6 | record-only(criteria 없음) | DecisionPolicy 미호출 |

## Recommendation → Verification lifecycle (MVP)

STEP 6A-9E-J.5-R에서 확정한 Product 계약이다. **아직 구현하지 않았다.**

`AnalysisRecommendation`과 `VerificationStatus`는 이름이 겹치는 값(`REVIEW_REQUIRED`)이 있어도 서로
다른 enum이다. 전자는 backend policy 출력이고 후자는 사용자 verification lifecycle 상태다. 아래가 둘
사이의 mapping이다.

### 원칙

**AI는 사용자를 자동으로 최종 탈락시키지 않는다.** AI Recommendation으로 인한
`VerificationStatus.REJECTED` 전환은 이 계약에 없다. 운영 방향은 PASS / guided retry /
unresolved hold 셋뿐이다. 동시에 무한 retry도 허용하지 않는다.

### Initial attempt

| `AnalysisRecommendation` | `VerificationStatus` |
|---|---|
| `PASS` | `APPROVED` |
| `REJECT_CANDIDATE` | `RETRY_REQUIRED` |
| `REVIEW_REQUIRED` | `RETRY_REQUIRED` |

첫 회차에서는 anomaly가 관찰되어도 바로 hold하지 않고 clean evidence를 한 번 더 요청한다. 부정행위
확정이 아니라 추가 확인 요청이다.

### Retry attempt (최대 1회)

| `AnalysisRecommendation` | `VerificationStatus` |
|---|---|
| `PASS` | `APPROVED` |
| `REJECT_CANDIDATE` | `REVIEW_REQUIRED` (hold) |
| `REVIEW_REQUIRED` | `REVIEW_REQUIRED` (hold) |

동일 scheduled opportunity 기준 **최초 제출 1회 + retry 1회 = 최대 2회 평가**. 추가 자동 retry는 없다.

### HOLD semantics

`VerificationStatus.REVIEW_REQUIRED`는 MVP에서 사실상 terminal이다. 자동 APPROVED 아님, 자동
REJECTED 아님, 자동 reward 없음, Progress 인정 안 됨, 추가 자동 Provider 호출 없음, 사용자 대상
fraud 확정 문구 없음.

**KNOWN MVP OPERATIONAL LIMITATION**: staff console·review queue·admin dashboard·approval API를 만들지
않으므로, hold된 Verification은 운영 리뷰 수단이 생길 때까지 미결로 남는다. `PersonalProgressCalculator`
는 `REVIEW_REQUIRED`를 `PENDING_DECISION`으로 분류하며, 이는 위 hold 의미와 일치한다.

### Provider failure는 retry를 소비하지 않는다

`AUTHENTICATION` / `RATE_LIMITED` / `PROVIDER_5XX` / `TIMEOUT` / `NETWORK` / `INVALID_RESPONSE` /
`INTERRUPTED`는 사용자 evidence에 대한 판단이 아니다. Recommendation을 만들지 않으므로 위 mapping에
진입하지 않고, 사용자 retry 횟수도 소비하지 않는다. Provider 재시도는 infrastructure 정책이다.

`VerificationAnalysis.attemptCount`는 **worker claim fencing/stale recovery용**이며 사용자 재제출
횟수가 아니다. 두 개념을 섞지 않는다.

### 구현 전 해소해야 할 blocker

이 계약은 현재 코드로 바로 구현할 수 없다. 확인된 blocker는 다음과 같다.

| # | 위치 | 내용 |
|---|---|---|
| 1 | `VerificationMedia` | `@OneToOne` + `UNIQUE(verification_id)`, `confirm()`은 재확인 불가. **재촬영 사진을 담을 자리가 없다** |
| 2 | `VerificationAnalysis` | `@OneToOne` + `UNIQUE(verification_id)`. **두 번째 분석 row를 만들 수 없다** |
| 3 | 전역 | 사용자 재제출 횟수를 표현하는 field가 없다 (`MISSING USER RETRY ATTEMPT MODEL`) |
| 4 | `VerificationCommandService` | `RETRY_REQUIRED`에서 3곳이 `"user retry is not enabled"`로 차단 |
| 5 | `PersonalProgressCalculator` | `RETRY_REQUIRED`를 만나면 예외를 던짐 |
| 6 | deadline | retry가 당일 deadline을 넘을 수 있는지에 대한 정책이 없다 |

`Verification` 자체는 재사용 가능하다. `UNIQUE(group_member_id, routine_schedule_id, scheduled_date)`
로 opportunity 당 1 row이고, `submit()`이 이미 `RETRY_REQUIRED → SUBMITTED`를 허용한다. 즉 상태
기계는 준비되어 있고, 막힌 것은 evidence/analysis 저장 구조와 명시적 차단이다.

기존 evidence는 감사 추적을 위해 삭제·덮어쓰기 하지 않는다. blocker 1·2의 해소 방향(1:N 전환 등)은
별도 STEP에서 결정한다.

## Deferred decisions

- Backend DecisionPolicy 구현(계약은 위에서 확정, threshold는 계속 NONE)
- Production AI Provider와 Scheduler
- guided retry 구현. 계약은 위에서 확정했고, 남은 것은 blocker 1~6이다.
- retry가 당일 deadline을 넘을 수 있는지: (A) original deadline 유지, (B) retry grace period,
  (C) 즉시 1회만 허용. 미결정이며 임의로 정하지 않는다.
- `REVIEW_REQUIRED` hold를 해소할 운영 수단(사람 또는 도구).
- 최종 거절 authority. 현재 `Verification.reject()`를 호출하는 production 코드는 없다.
- Reward/Heart/Ranking. 해당 system이 아직 존재하지 않으므로 `APPROVED` 이후 정책은 미정이다.
- Evaluation dataset의 실제 PHOTO asset과 수집된 observation. Human Label 계약과 offline threshold
  sweep harness는 [Meal PHOTO Evaluation Dataset and Human Label Contract](verification-evaluation-dataset.md)에
  test scope로 존재하며, selected threshold는 여전히 없다.
- category taxonomy
- 기록형 Group의 Progress/reward 세부 정책

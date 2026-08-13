# Verification AI Criteria and Observation Contract

이 문서는 STEP 6A-9E-G/H의 구현 계약이다. 실제 루틴별 인증 기준, active version이나 AI 판정 임계값을 확정하지 않는다.

## Authority

```text
Routine
→ Backend-owned Versioned Criteria
→ Media
→ AI Provider
→ Structured Observations

Structured Observations + Versioned Criteria
→ Backend DecisionPolicy (후속 STEP)
→ AnalysisRecommendation
```

AI Provider는 `PASS`, `REVIEW_REQUIRED`, `REJECT_CANDIDATE`, `VerificationStatus`, Reward, Progress를
결정하거나 반환하지 않는다. `VerificationAnalysisSuccessResult`는 Provider 결과가 아니라 향후 Backend
DecisionPolicy가 recommendation과 criteria provenance를 결합한 persistence 입력이다.

## Storage decision

| Option | 현재 판단 |
|---|---|
| DB version table | Product criteria와 admin/change lifecycle이 없어 빈 schema만 추가하게 됨 |
| Code-owned immutable contract | 선택. 배포와 code review로 변경을 통제하고 test fixture로 평가 가능 |
| Versioned JSON/resource | Parser/schema/배포 경로만 늘고 현재 Product content가 없음 |
| RoutineDefinition 직접 필드 | mutable description과 criteria version을 혼합하므로 사용하지 않음 |

현재는 `VerificationCriteria` contract만 존재하고 Production catalog entry는 0개다. Product 기준이 확정되면
동일 ID의 기존 version을 수정하지 않고 새 version을 추가한다. Catalog/Resolver와 active-version 선택은 실제
entry와 Product binding 정책이 생길 때 구현한다.

## Stable routine identity

`routine_definition.id`는 환경별 DB identity이고 `name`/`description`은 display text이므로 Criteria나 Evaluation의
Product identity로 사용하지 않는다. `RoutineKey`를 별도 stable identity로 사용한다.

- canonical format: trim 후 `Locale.ROOT` uppercase
- allowed format: `[A-Z][A-Z0-9_]{0,63}`
- maximum length: 64
- DB column: `routine_definition.routine_key VARCHAR(64) NULL UNIQUE`
- immutable semantics: 생성 시에만 지정하고 update API/setter를 제공하지 않음
- rename: display name 변경은 RoutineKey/Criteria identity를 변경하지 않음

V7은 기존 row의 Product 의미를 추측하지 않고 `NULL`로 유지한다. 실제 Product key가 확정된 row만 별도 운영
절차로 backfill한 후, 신규 unkeyed Routine 생성 차단이나 `NOT NULL` 강화 여부를 결정한다. 현재 Production
Routine 생성/수정/삭제 API와 seed는 없다.

## Criteria identity and version

- `criteriaId`: Backend가 소유하는 stable identity, 최대 48자, `@` 사용 금지
- `version`: 1 이상의 numeric revision
- persisted reference: `<criteriaId>@<version>`
- `routineKey`: Criteria가 결합되는 stable Product Routine identity
- Vendor-facing `ProviderContract`: `routineKey`를 제외함

기존 `verification_analysis.criteria_version VARCHAR(64)`에는 전체 persisted reference를 저장할 수 있다.
Provider response에서 criteria identity/version을 받거나 신뢰하지 않는다. Persisted reference parser는
`<criteriaId>@<positive integer>` canonical form만 허용한다.

## Binding decision

검토한 방식은 다음과 같다.

| Option | 판단 |
|---|---|
| Criteria가 RoutineKey를 소유 | 선택. immutable criteria 자체가 binding을 명시하고 DB query가 없음 |
| 별도 code map | Product criteria 0개 상태에서는 빈 scaffolding이며 active-version 정책을 암묵적으로 만듦 |
| DB binding table | 운영 변경/admin lifecycle이 없어 현재는 과설계 |
| RoutineDefinition에 current criteria 저장 | mutable current pointer와 immutable historical provenance를 혼합함 |

Criteria-aware `VerificationAnalysis.createPending`은 Verification의 실제 RoutineKey와 Criteria의 RoutineKey가
같을 때만 Criteria Reference를 저장한다. 따라서 환경별 Routine DB ID를 코드에 하드코딩하지 않는다.

## Criteria content

- `supportedMedia`: `PHOTO`, `VIDEO`를 표현하지만 실제 Product 지원 여부를 확정하지 않음
- `requiredObservations`: 해당 criteria가 요구하는 observation vocabulary
- `evidenceRequirements`: Vendor prompt가 아닌 provider-neutral Product evidence requirement

`RoutineDefinition.description`은 인증 기준이나 Prompt로 사용하지 않는다.

## Observation semantics

현재 DB 컬럼을 재사용하며 의미를 다음과 같이 제한한다.

- `objectPresence`: criteria가 지정한 target evidence가 media에서 관찰 가능한지 여부. 임의의 물체나 사용자
  신원 확인을 뜻하지 않는다.
- `relevanceScore`: media가 주어진 criteria의 observation을 수행하는 데 얼마나 관련 있는지에 대한 `0..1`
  측정값. PASS 확률이나 recommendation threshold가 아니다.
- `anomalyDetected`: criteria가 요구한 evidence integrity 관점에서 시각적 이상 징후가 관찰되었는지 여부.
  비교 reference 없이 중복 업로드나 replay를 판정하지 않는다.
- `framedProperly`: criteria가 요구한 evidence를 화면 안에서 평가할 수 있는지 여부. 미적 품질 평가가 아니다.
- nullable observation: Provider가 해당 값을 평가할 수 없음을 뜻한다.

`OBSERVATION_COMPLETE`일 때 criteria가 요구한 observation은 모두 non-null이어야 한다. Partial/insufficient
응답은 null로 불확실성을 표현할 수 있으며, Backend는 이를 PASS threshold로 해석하지 않는다.

Provider reason code는 자유 문자열이 아니라 다음 bounded vocabulary만 사용한다.

- `OBSERVATION_COMPLETE`
- `OBSERVATION_PARTIAL`
- `OBSERVATION_INSUFFICIENT`
- `POTENTIAL_INTEGRITY_ANOMALY`

Reason code는 recommendation이 아니며 Product 합격/거부 사유를 대신하지 않는다.

## Media and privacy boundary

Provider boundary에는 다음만 전달한다.

- RoutineKey가 제거된 versioned criteria contract
- Domain media modality
- normalized Content-Type
- media bytes

다음 값은 Provider boundary에 노출하지 않는다.

- `analysisId`
- `analysisRequestId`
- `attemptCount`
- `verificationId`
- S3 object key 또는 presigned URL
- User/Group identity

Media는 evidence이지 instruction이 아니다. 실제 Prompt 구현 시 media 내부 텍스트를 system instruction으로
취급하지 않아야 한다.

## Analysis provenance and timing

Criteria Reference는 Verification 제출과 Analysis enqueue가 원자적으로 이루어지는 시점에
`verification_analysis.criteria_version`에 고정한다. Worker claim/recovery/reclaim은 이 값을 변경하지 않는다.
InputLoader는 고정된 reference가 없거나 canonical form이 아니면 Provider processing을 거부하고, MediaProcessor는
resolve된 Criteria reference와 enqueue provenance가 다르면 Provider를 호출하지 않는다. ResultService는 caller가
전달한 expected reference와 row의 reference가 일치할 때만 성공 결과를 저장하며 reference 자체는 갱신하지 않는다.

Verification 생성, upload 또는 Worker claim 시점을 사용하지 않는다. 특히 Worker 실행 시 최신 version을 선택하면
같은 Analysis의 Attempt 1과 Attempt 2가 다른 기준을 사용할 수 있어 재현성과 attempt fencing을 깨뜨린다.

Product criteria와 active-version 선택 소스가 아직 없으므로 현재 Verification 제출 경로는 legacy nullable enqueue를
유지한다. 이 경로는 제출 호환성만 위한 것이며 Provider 성공 경로로 처리할 수 없다. Production Processor 활성화
전에 CommandService가 resolve된 Criteria를 Criteria-aware enqueue에 전달하도록 연결해야 한다.

## Deferred decisions

- PHOTO/VIDEO Product 지원 범위
- 실제 criteria content와 version 1 entry
- 실제 RoutineKey 값과 기존 row backfill
- 어떤 Criteria version을 선택할지에 대한 Product/deployment policy
- Verification 제출 경로의 Criteria resolver 연결
- Evaluation dataset과 human label
- Backend DecisionPolicy와 threshold
- AnalysisRecommendation에서 VerificationStatus로 가는 별도 policy

위 결정 전까지 Production `VerificationAnalysisProcessor` Bean과 Scheduler는 활성화하지 않는다. Evaluation fixture는
향후 DB ID 대신 `routineKey + criteriaReference + media fixture + expected observation + human label`로 재현할 수 있다.

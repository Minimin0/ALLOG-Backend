# Verification AI Criteria and Observation Contract

이 문서는 STEP 6A-9E-G의 구현 계약이다. 실제 루틴별 인증 기준이나 AI 판정 임계값을 확정하지 않는다.

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
| DB version table | Product criteria, stable routine key, admin/change lifecycle이 없어 빈 schema만 추가하게 됨 |
| Code-owned immutable contract | 선택. 배포와 code review로 변경을 통제하고 test fixture로 평가 가능 |
| Versioned JSON/resource | Parser/schema/배포 경로만 늘고 현재 Product content가 없음 |
| RoutineDefinition 직접 필드 | mutable description과 criteria version을 혼합하므로 사용하지 않음 |

현재는 `VerificationCriteria` contract만 존재하고 Production catalog entry는 0개다. Product 기준이 확정되면
동일 ID의 기존 version을 수정하지 않고 새 version을 추가한다. Catalog/Resolver는 실제 entry와 binding 정책이
생길 때 구현한다.

## Criteria identity and version

- `criteriaId`: Backend가 소유하는 stable identity, 최대 48자, `@` 사용 금지
- `version`: 1 이상의 numeric revision
- persisted reference: `<criteriaId>@<version>`
- `routineDefinitionId`: resolve된 criteria의 내부 routine binding
- Vendor-facing `ProviderContract`: `routineDefinitionId`를 제외함

기존 `verification_analysis.criteria_version VARCHAR(64)`에는 전체 persisted reference를 저장할 수 있다.
Provider response에서 criteria identity/version을 받거나 신뢰하지 않는다.

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

- Internal routine ID가 제거된 versioned criteria contract
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

## Analysis binding timing

Criteria version은 재현성을 위해 Verification 제출과 Analysis enqueue가 원자적으로 이루어지는 시점에 고정하는
것을 권장한다. 현재 Product criteria와 routine-to-criteria binding이 없으므로 이 STEP에서는 enqueue contract나
schema를 변경하지 않는다. Worker claim 시 최신 criteria를 조회하는 방식은 동일 Analysis가 실행 시점에 따라 다른
기준을 사용할 수 있어 사용하지 않는다.

## Deferred decisions

- RoutineDefinition과 criteria ID를 연결할 stable Product key
- PHOTO/VIDEO Product 지원 범위
- 실제 criteria content와 version 1 entry
- Analysis enqueue 시 criteria reference persistence
- Evaluation dataset과 human label
- Backend DecisionPolicy와 threshold
- AnalysisRecommendation에서 VerificationStatus로 가는 별도 policy

위 결정 전까지 Production `VerificationAnalysisProcessor` Bean과 Scheduler는 활성화하지 않는다.

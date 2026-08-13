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

Provider boundary에는 TemplateKey, Analysis/Verification/User/Group ID, S3 key나 URL을 보내지 않는다. Criteria의
provider-neutral evidence requirement, modality, normalized Content-Type과 media bytes만 전달한다.

## Deferred decisions

- Backend DecisionPolicy와 threshold
- Production AI Provider와 Scheduler
- retry UX
- Evaluation dataset과 human labels
- category taxonomy
- 기록형 Group의 Progress/reward 세부 정책

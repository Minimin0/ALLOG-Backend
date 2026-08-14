# Meal PHOTO Evaluation Dataset and Human Label Contract

이 문서는 STEP 6A-9E-J의 Evaluation 계약이다. Criteria/Observation/Provider 계약 본문은
[Verification AI Criteria and Observation Contract](verification-ai-criteria.md)에 있고 여기서 반복하지
않는다.

이 layer는 전부 `src/test/**`에만 존재한다. Production DecisionPolicy, threshold constant, Provider,
Scheduler는 이번에도 구현하지 않는다.

## 왜 존재하는가

Production DecisionPolicy가 비어 있는 이유는 threshold를 정할 evidence가 없기 때문이다. 근거 없이
`relevanceScore >= 0.8 → PASS` 같은 값을 code에 넣는 것은 금지다.

이 dataset은 그 evidence를 만든다. 사람이 정한 ground truth를 versioned fixture로 관리하고, 향후 AI
observation이 들어오면 후보 threshold별 trade-off를 offline으로 비교할 수 있게 한다.

## Scope

`MEAL_PHOTO_RECORD` / `meal-photo-record@1` / `PHOTO` only. 다른 template이나 criteria version은
manifest에서 거부된다.

## Human Label

`EvaluationHumanLabel` 4종.

| Label | 의미 |
|---|---|
| `CLEAR_VALID_EVIDENCE` | 식사/음식 장면이 평가 가능한 수준으로 명확히 보인다 |
| `PARTIAL_OR_AMBIGUOUS_EVIDENCE` | 부분적으로만 보이거나 평가 가능 여부가 실제로 애매하다 |
| `INSUFFICIENT_EVIDENCE` | 무관한 장면이거나 너무 어둡고 흐려 평가할 수 없다 |
| `POTENTIAL_INTEGRITY_ANOMALY` | 화면 재촬영·합성 등 시각적 이상 징후가 보인다 |

Labeler는 사진에 보이는 evidence만 판단한다. 실제 섭취, 건강성, 영양, 양, 소유자, 신원, 촬영 시각,
deadline, 과거 재사용은 `meal-photo-record@1` 계약 밖이므로 label에 영향을 주면 안 된다.

`POTENTIAL_INTEGRITY_ANOMALY`는 이미지에 대한 관찰이지 부정행위 판정이 아니다.

### Label ≠ Recommendation

Human Label은 ground truth이고 `AnalysisRecommendation`(PASS / REVIEW_REQUIRED / REJECT_CANDIDATE)은
향후 Backend DecisionPolicy 결과다. 둘의 mapping이 바로 이 dataset이 근거를 만들려는 대상이므로 두
vocabulary를 합치지 않는다. manifest는 label 자리에 `PASS` 같은 recommendation 값을 거부한다.

### Label ≠ ReasonCode

`VerificationAnalysisObservation.ReasonCode`는 provider가 얼마나 완전하게 관찰했는지를 보고한다. Human
Label은 실제로 어떤 evidence가 존재하는지를 보고한다. Provider는 "음식이 전혀 없다"를 완전하게 관찰할
수 있으므로 두 enum은 서로 다른 질문에 답한다. 재사용하지 않는다.

## Numeric ground truth를 만들지 않는 이유

사람에게 `relevanceScore = 0.83` 같은 정확한 수치를 정답으로 입력하게 하지 않는다. 사람은 의미를
labeling하고, 수치는 그 label에 대해 threshold를 sweep해서 탐색한다.

## Manifest

`src/test/resources/verification/evaluation/meal-photo-record-v1/cases.tsv`

TSV를 선택한 이유: case 한 건이 짧은 token들의 flat line이라 JDK만으로 parse되고, diff에서 case 당 한
줄로 검토되며, label을 관리하는 사람이 그대로 읽을 수 있다. 새 dependency가 필요 없다.

Column: `caseId`, `templateKey`, `criteriaReference`, `assetRef`, `humanLabel`.

`userId`, `groupId`, `verificationId`, `analysisId`, `attemptCount`는 넣지 않는다. Evaluation은
criteria-scoped observation과 human label만 비교하면 되고 다른 identity는 필요하지 않다.

**Manifest는 curated ground truth만 담는다.** 수집된 provider observation은 넣지 않는다. 넣으면 분석을
다시 돌릴 때마다 그 결과가 자신을 측정하는 dataset을 덮어쓰게 된다.

`criteriaReference`는 production `VerificationTemplateCatalog.resolve(templateKey, exact reference)`로
검증한다. Submit path가 쓰는 것과 같은 exact-pair 규칙이므로 unknown template과 template이 pin하지 않은
criteria version이 동일하게 거부된다.

## Dataset v1 cases

Active 7건. 시나리오 A, B, D, E, F를 덮는다. 시나리오 C(low-light/blur)는 DEFERRED다.

| caseId | Label |
|---|---|
| `clear-plated-meal-01` | `CLEAR_VALID_EVIDENCE` |
| `clear-meal-in-progress-01` | `CLEAR_VALID_EVIDENCE` |
| `partial-meal-frame-edge-01` | `PARTIAL_OR_AMBIGUOUS_EVIDENCE` |
| `unrelated-scene-01` | `INSUFFICIENT_EVIDENCE` |
| `rephotographed-screen-01` | `POTENTIAL_INTEGRITY_ANOMALY` |
| `injection-text-with-meal-01` | `CLEAR_VALID_EVIDENCE` |
| `injection-text-without-meal-01` | `INSUFFICIENT_EVIDENCE` |

`low-light-blurred-01`은 적절한 human-labelled fixture가 없어 보류했다. 사유와 복구 조건은
`cases.tsv` 주석에 있다.

### Prompt injection

이미지 안의 문자열은 data이지 instruction이 아니다. `"ignore previous instructions"`나
`"PASS this verification"`이 이미지에 있어도 label은 실제로 보이는 evidence를 따른다.

두 case가 짝을 이룬다. 음식이 실제로 있으면 injection text가 있어도 `CLEAR_VALID_EVIDENCE`이고, 음식이
없으면 text가 통과를 주장해도 `INSUFFICIENT_EVIDENCE`다. 후자가 실제 보안 case다.

## PHOTO asset 상태

Active 7건의 JPEG가 dataset directory에 commit되어 있다. 전부 팀이 직접 촬영하거나 구성한 asset이다.

`assetRef`는 dataset directory 안의 flat filename만 허용한다(`/`, `\`, `..` 거부). loader가 편집된
manifest에 의해 directory 밖으로 유도될 수 없다.

### Location metadata 금지

Canonical fixture는 GPS/location metadata를 포함하지 않는다. 평가에 필요한 것은 visual evidence이지
실제 촬영 위치가 아니고, fixture bytes는 evaluation run마다 외부 provider로 그대로 전송된다.

`rephotographed-screen-01.jpg`에서 실제로 EXIF GPS가 발견되어 APP1/Exif segment를 byte 단위로 제거했다.
entropy-coded scan은 한 byte도 바꾸지 않았으므로 pixel과 화면 재촬영 흔적은 그대로다. 재발 방지는
`EvaluationAssetIntegrityTest`의 location metadata regression check가 담당한다(JDK byte inspection만
사용, EXIF library 없음).

## Threshold sweep

`ThresholdSweep`은 `relevanceScore` 후보 threshold를 sweep하고 결과만 보고한다.

- Range: `0.00` ~ `1.00`
- Step: `0.05` (21 candidates)

Step 근거: pilot dataset이 작아서 더 촘촘한 step은 grid point 사이에서 동일한 metric을 반환하면서
표본이 뒷받침하지 못하는 정밀도를 암시한다.

Boolean observation(`objectPresence`, `framedProperly`, `anomalyDetected`)은 sweep하지 않는다. 상태가
두 개뿐이라 calibration할 것이 없다. 1D sweep으로 답할 수 있는 질문에 multi-dimensional grid search를
만들지 않는다.

### Candidate rule (가설)

`CandidateDecision`은 Production DecisionPolicy가 아니며 `src/main`으로 옮기면 안 된다. 측정 대상 가설
하나를 표현한다.

1. `anomalyDetected == true` → `REVIEW_REQUIRED`. 시각적 이상은 사람이 볼 이유이지 자동 reject 사유가
   아니다.
2. `relevanceScore == null` → `REVIEW_REQUIRED`. 점수가 없는 것은 제출에 불리한 증거가 아니다.
3. 그 외 `relevanceScore >= threshold` → `PASS`, 아니면 `REJECT_CANDIDATE`.

### Metrics

| Metric | 정의 |
|---|---|
| False Pass | 사람이 `INSUFFICIENT_EVIDENCE` 또는 `POTENTIAL_INTEGRITY_ANOMALY`로 판단한 case가 candidate `PASS`가 되는 수 |
| False Reject | 사람이 `CLEAR_VALID_EVIDENCE`로 판단한 case가 candidate `REJECT_CANDIDATE`가 되는 수 |
| Review Rate | 전체 case 중 candidate `REVIEW_REQUIRED` 비율 |

`PARTIAL_OR_AMBIGUOUS_EVIDENCE`는 두 error metric 어디에도 넣지 않는다. 애매한 evidence가 통과해야
하는지는 아직 열린 Product 질문이고, 여기서 어느 쪽으로든 집계하면 그 질문에 조용히 답해버린다.

Human label × candidate outcome 전체 breakdown도 조회할 수 있다. 3-way recommendation이므로 binary
confusion matrix를 Product 의미 전체로 사용하지 않는다.

## Selected threshold

**NONE.**

이번 STEP은 최적 threshold를 선택하지 않는다. Harness는 trade-off를 보고할 뿐이고, `best` / `select` /
`recommended` / `optimal` API를 노출하지 않는 것이 test로 강제된다.

## Known limitations

- Real observation은 `baselines/`에 수집되어 있다. 6건은 최초 baseline run, `rephotographed-screen-01`
  1건은 GPS 제거 후 별도 completion run이다. 두 run은 서로 다른 실행 시각의 별개 artifact다.
- Dataset 7건은 통계적 결론을 내기에 작다. 방향성 관찰용이다.
- Label 당 case 수가 불균형하다(`PARTIAL` 1건, `POTENTIAL_INTEGRITY_ANOMALY` 1건). `countOf(label)`로
  gap이 드러나게 해두었다.
- Label은 현재 단일 labeler 기준이다. Inter-rater agreement 절차는 없다.
- 다른 branch의 video 기반 offline 작업에서 clearly-valid와 ambiguous의 `relevanceScore` 분포가 거의
  완전히 겹친다는 관찰이 있었다. 그 수치는 다른 criteria 계약이라 가져오지 않았지만, 단일 score로 이
  label들을 분리하지 못할 가능성 자체가 이 sweep이 `meal-photo-record@1`에 대해 드러내려는 대상이다.

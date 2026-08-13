# STAGE7 임계치 캘리브레이션 보고서

`calibration-images/`의 라벨링된 이미지 19장(+ pHash 검증용 duplicate 쌍 1개)을 **실제 STAGE6
Vision AI 모듈**(`ClaudeVisionAnalysisClient` → `RoutineVerificationVisionAnalysisService`, mock 아님,
실제 Anthropic API 호출)에 통과시켜 수집한 원본 데이터를 근거로 작성했다. 사용한 모델: `claude-sonnet-5`.

재현용 도구: `./gradlew runCalibration` (환경변수 `ANTHROPIC_API_KEY` 필요, 소스는
`src/main/java/com/allog/allogbe/tools/calibration/CalibrationRunner.java` — 프로덕션 Spring
컴포넌트 스캔에는 관여하지 않는 일회성 분석 도구).

## 1. 카테고리별/라벨별 이미지 개수 요약

| 카테고리 | pass | review | reject_unrelated | reject_anomaly | 합계 |
|---|---|---|---|---|---|
| skincare | 2 | 1 | 1 | 1 | 5 |
| meal | 2 | 1 | 1 | 1 | 5 |
| exercise | 2 | 1 | 1 | 1 | 5 |
| sleep | 2 | 1 | 1 | **0** ⚠️ | 4 |
| duplicates | pair1 (original+resubmit) | | | | 2 |

**경고**: `sleep`에는 `reject_anomaly` 라벨 이미지가 0장이다 (다른 카테고리는 전부 1장씩). 이 보고서의
sleep 카테고리 "reject" 통계는 `reject_unrelated` 1건에만 의존하며, "수면 인증에서 조작/이상징후가
있는 이미지"에 대한 실측 데이터는 없다.

## 2. 원본 수집 데이터 (재검증용 원본 표)

전체 원본(요약 텍스트 포함)은 [`full-postfix-results.tsv`](full-postfix-results.tsv)(최종, 수정된
프롬프트 기준) 및 [`sleep-prefix-baseline.tsv`](sleep-prefix-baseline.tsv)(sleep 전용, 수정 전
베이스라인)에 탭 구분 원본 그대로 첨부했다. 아래는 핵심 지표만 발췌한 표다 (최종/수정 후 기준).

| 카테고리 | 파일명 | 라벨 | objectPresence | relevanceScore | anomalyFlags 개수 | confidence |
|---|---|---|---|---|---|---|
| skincare | pass_01.jpg | pass | true | 0.85 | 3 | 0.6 |
| skincare | pass_02.jpg | pass | true | 0.55 | 3 | 0.5 |
| skincare | reject_anomaly_01.jpg | reject(anomaly) | true | 0.65 | 2 | 0.55 |
| skincare | reject_unrelated_01.jpg | reject(unrelated) | false | 0.02 | 1 | 0.9 |
| skincare | review_01.png | review | true | 0.85 | 0 | 0.7 |
| meal | pass_01.jpg | pass | true | 0.95 | 0 | 0.85 |
| meal | pass_02.jpg | pass | true | 0.95 | 0 | 0.85 |
| meal | reject_anomaly_01.jpg | reject(anomaly) | true | 0.75 | 3 | 0.55 |
| meal | reject_unrelated_01.jpg | reject(unrelated) | false | 0.02 | 2 | 0.9 |
| meal | review_01.png | review | true | 0.85 | 0 | 0.7 |
| exercise | pass_01.jpg | pass | true | 0.8 | 0 | 0.75 |
| exercise | pass_02.jpg | pass | true | 0.75 | 0 | 0.7 |
| exercise | reject_anomaly_01.jpg | reject(anomaly) | true | 0.4 | 3 | 0.35 |
| exercise | reject_unrelated_01.jpg | reject(unrelated) | false | 0.02 | 2 | 0.9 |
| exercise | review_01.png | review | true | 0.55 | 2 | 0.5 |
| sleep | pass_01.png | pass | true | 0.7 | 0 | 0.6 |
| sleep | pass_02.png | pass | true | 0.55 | 2 | 0.55 |
| sleep | reject_unrelated_01.jpg | reject(unrelated) | false | 0.02 | 2 | 0.9 |
| sleep | review_01.png | review | true | 0.8 | 0 | 0.65 |

## 3. 라벨별 점수 분포 요약 (relevanceScore, 전 카테고리 통합)

| 라벨 그룹 | n | min | max | 평균 | 개별 값 |
|---|---|---|---|---|---|
| pass | 8 | 0.55 | 0.95 | 0.76 | 0.85, 0.55, 0.95, 0.95, 0.8, 0.75, 0.7, 0.55 |
| review | 4 | 0.55 | 0.85 | 0.76 | 0.85, 0.85, 0.55, 0.8 |
| reject (전체) | 7 | 0.02 | 0.75 | 0.27 | 0.65, 0.02, 0.75, 0.02, 0.4, 0.02, 0.02 |
| reject_unrelated만 | 4 | 0.02 | 0.02 | 0.02 | 0.02, 0.02, 0.02, 0.02 |
| reject_anomaly만 | 3 | 0.4 | 0.75 | 0.60 | 0.65, 0.75, 0.4 |

**핵심 관찰**: `pass`와 `review` 그룹의 relevanceScore 범위가 **완전히 겹친다**(둘 다
0.55~0.95/0.85 근방). `reject_anomaly`(0.4~0.75)도 `review`(0.55~0.85), 심지어 `pass`(0.55~0.95)의
하단부와 겹친다. 반면 `reject_unrelated`(0.02 고정)는 나머지 전부와 뚜렷이 분리된다.

## 4. LOW_THRESHOLD / MID_THRESHOLD 잠정 권고값과 근거

지시된 계산 방식(“reject 최댓값과 review 최솟값 사이”, “review 최댓값과 pass 최솟값 사이”)을
그대로 적용하면:

- **LOW_THRESHOLD 후보** = mid(reject_max=0.75, review_min=0.55) = **0.65**
- **MID_THRESHOLD 후보** = mid(review_max=0.85, pass_min=0.55) = **0.70**

**⚠️ 이 두 값을 그대로 코드에 반영하는 것은 권장하지 않는다.** 3절의 분포에서 보듯 reject_max(0.75)가
review_min(0.55)보다 크고, review_max(0.85)가 pass_min(0.55)보다 커서 — 즉 "사이 지점"이라는 전제
자체가 이 표본에서는 성립하지 않는다(그룹이 역전/중첩되어 있음). 이 상태로 0.65/0.70을 하드코딩하면
reject_anomaly의 상당수(0.65, 0.75)가 여전히 LOW_THRESHOLD를 넘어가고, review의 대부분(0.8, 0.85)이
MID_THRESHOLD를 넘어가 버려 분리 효과가 거의 없다.

**대안 권고 (근거 포함):**

1. **LOW_THRESHOLD ≈ 0.2** (잠정): `reject_unrelated`(objectPresence=false 케이스)만 놓고 보면
   0.02 vs 나머지 그룹 최소값 0.4~0.55 사이에 매우 넓고 깨끗한 간격이 있다. 이 값은 "objectPresence
   판정과 무관하게 relevanceScore 자체가 극단적으로 낮은 경우"를 잡아내는 **보조 안전장치**로만
   쓰기를 권한다 — 현재도 `!objectPresence` 규칙이 이 케이스를 이미 100% 잡아내고 있으므로
   (`reject_unrelated` 4건 전부 objectPresence=false로 정확히 탐지됨), LOW_THRESHOLD는 모델이
   objectPresence 판단에서 실수했을 때의 이중 안전망 역할이다.
2. **MID_THRESHOLD는 relevanceScore 단독으로 신뢰성 있게 산출할 수 없다.** review와 pass의 분포가
   본질적으로 겹치기 때문에(같은 relevanceScore라도 사람은 다르게 라벨링함 — 7절 오분류 사례 참고),
   숫자 하나로 나누는 대신 **기존 `RELEVANCE_THRESHOLD=0.5`를 잠정 유지**하고, anomalyFlags 유무를
   1차 분리 기준으로 계속 사용하는 현재 규칙을 유지할 것을 권한다. 데이터가 더 쌓이기 전까지는
   relevanceScore 기반의 2단 임계치(LOW/MID) 도입 자체를 보류하는 편이 "근거 없는 임계치"보다 낫다고
   판단했다.
3. **더 중요한 발견**(6절과 연결): reject_anomaly 이미지 3건 전부 objectPresence=true로 나와
   relevanceScore/현재 규칙으로는 REJECT_CANDIDATE에 도달하지 못하고 REVIEW_REQUIRED(NORMAL
   우선순위)에 머문다. 화면 재촬영/모아레 패턴처럼 **명백한 조작 신호는 relevanceScore와 별개로
   anomalyFlags의 내용(키워드)에 따라 즉시 REJECT_CANDIDATE/HIGH로 격상**하는 규칙을 추가하는 편이,
   relevanceScore 임계치를 정교화하는 것보다 훨씬 효과가 크고 근거도 명확하다. (코드 변경은 하지
   않았음 — 사용자 확인 후 STAGE7 규칙엔진에 반영 여부 결정 필요.)

## 5. sleep 카테고리 스크린샷 오탐 검증 결과 및 조치

**검증 절차**: 수정 전 프롬프트로 sleep 4장을 먼저 실행 → 결과 확인 → 예방적 수정 적용 → sleep 포함
전체 재실행하여 동일 이미지로 전/후 비교.

| 파일 | 수정 전 relevance/anomalies | 수정 후 relevance/anomalies |
|---|---|---|
| pass_01.png (진짜 알람 스크린샷) | 0.75 / **[] (이상없음)** | 0.7 / **[] (이상없음)** |
| pass_02.png (디자인 목업으로 추정) | 0.6 / 3건("실기기 재촬영 아님/일러스트 의심" 등) | 0.55 / 2건("iOS 26 alarm clock (huuuuge)" 라벨 텍스트 이상, 상태바 요소 부재) |
| review_01.png (잠금화면 캡처) | 0.55 / **[] (이상없음)** | 0.8 / **[] (이상없음)** |

**결론**: 가설했던 "스크린샷이라는 이유만으로 이상징후 처리"되는 **명백한 오탐은 이 표본(진짜
스크린샷 2장)에서 재현되지 않았다** — `pass_01`, `review_01` 모두 수정 전부터 이미 깨끗했다.
`pass_02`만 계속 플래그가 붙는데, 실제 이미지(제가 직접 열람 확인)를 보면 상단에
**"iOS 26 alarm clock (huuuuge)"라는, 실제 iOS UI에는 존재할 수 없는 디자인 목업 캡션 텍스트**가
박혀 있어 — AI의 판단이 타당하다. 즉 이건 프롬프트 버그가 아니라 **이미지 자체가 목업/에지케이스**인
경우다 (7절 참고).

**그럼에도 조치**: 표본이 2장뿐이라 오탐 위험을 완전히 배제할 수 없다고 판단해, `VisionAnalysisPromptBuilder`에
SLEEP 카테고리 전용 예외 문구를 예방적으로 추가했다 (`categoryGuidance()` 메서드, "화면을 촬영/캡처했다는
사실 자체만으로는 anomalyFlags에 포함시키지 말 것" 명시). 수정 후에도 `pass_02`의 플래그가 유지된 것은
이 조치가 **정당한 이상징후까지 무차별로 지워버리지 않는다**는 것을 보여주는 긍정적 신호로 해석했다.
`VisionAnalysisPromptBuilderTest` 등 기존 STAGE6 테스트는 전부 통과 확인.

## 6. duplicates 쌍 pHash 유사도 결과

| 쌍 | 해밍 거리 | 현재 임계치(≤10) 대비 |
|---|---|---|
| pair1_original.jpg vs pair1_resubmit.png | **6** | 통과 (여유 4bit) — 중복으로 정확히 탐지됨 |

**평가**: jpg 원본을 png로 재인코딩해 재제출한 실제 시나리오를 잘 재현한 쌍으로 보이며, 현재
`HAMMING_DISTANCE_THRESHOLD=10`이 이 케이스를 정확히 잡아낸다. 다만 **표본이 쌍 1개뿐**이라 "false
positive(전혀 다른 사진인데 우연히 해밍 거리가 가까운 경우)"에 대한 검증은 하지 못했다 — 임계치의
하한(너무 낮아서 실제 재사용을 놓치는 경우)만 확인했고 상한(너무 높아서 무관한 사진을 오탐하는 경우)은
미검증 상태로 남는다.

## 7. 오분류 사례 목록 및 원인 추정

현재 STAGE7 규칙(`!objectPresence→REJECT_CANDIDATE`, `anomalies 있거나 relevance<0.5→REVIEW_REQUIRED`,
`그 외→PASS`)을 그대로 적용했을 때 사람 라벨과 다르게 나오는 9건 (19건 중):

| # | 이미지 | 사람 라벨 | 규칙 예측 | 원인 분류 | 설명 |
|---|---|---|---|---|---|
| 1 | skincare/pass_01 | pass | REVIEW_REQUIRED | **프롬프트 문제** | "화보/광고 스타일" 의심 플래그 — 잘 찍은 정상 사진도 상업적 구도로 보이면 과민 반응 |
| 2 | skincare/pass_02 | pass | REVIEW_REQUIRED | **이미지 문제** | 눈 감은 근접샷, 제품 미노출 — 사람이 봐도 애매할 수 있는 촬영 |
| 3 | skincare/reject_anomaly_01 | reject | REVIEW_REQUIRED | **규칙엔진 설계 gap** | 모아레/이중이미지 감지했지만 objectPresence=true라 REJECT_CANDIDATE 도달 불가 |
| 4 | skincare/review_01 | review | PASS | **임계치 문제** | anomalies=0, relevance=0.85로 pass 임계치(0.5) 초과 — review/pass 그룹 자체가 relevanceScore로 안 갈림 |
| 5 | meal/reject_anomaly_01 | reject | REVIEW_REQUIRED | **규칙엔진 설계 gap** | #3과 동일 패턴(모니터 재촬영+마우스 커서 감지) |
| 6 | meal/review_01 | review | PASS | **임계치 문제** | #4와 동일 패턴 |
| 7 | exercise/reject_anomaly_01 | reject | REVIEW_REQUIRED | **규칙엔진 설계 gap** | relevance=0.4로 비교적 낮음에도 objectPresence=true라 REJECT_CANDIDATE 미도달 |
| 8 | sleep/pass_02 | pass | REVIEW_REQUIRED | **이미지 문제** | 5절 참고 — 실제로 목업처럼 보이는 이미지, AI 판단이 타당해 보임 |
| 9 | sleep/review_01 | review | PASS | **임계치 문제** | #4와 동일 패턴 |

**원인별 집계**: 규칙엔진 설계 gap 3건, 임계치 문제 3건, 프롬프트 과민반응 1건, 이미지 자체가
edge-case 2건. **프롬프트 버그로 확정할 수 있는 사례는 0건**이었다(5절 sleep 건도 오탐 아님으로
결론). 가장 반복적이고 근거가 뚜렷한 패턴은 "review vs pass가 relevanceScore로 안 갈린다"(3건)와
"anomaly가 있어도 objectPresence=true면 REJECT_CANDIDATE에 못 감"(3건) 두 가지였다.

## 8. 한계 및 주의사항

- **표본 극소**: 카테고리당 라벨별 1~2장(sleep reject_anomaly는 0장). 통계적으로 유의미한 임계치를
  산출하기엔 턱없이 부족한 양이다. 이번 보고서의 모든 숫자는 "잠정값"이며, 실 운영 데이터(라벨당
  최소 20~30장 권장)로 반드시 재조정해야 한다.
- **동일 이미지 재호출 시 점수가 흔들린다**: sleep/review_01을 수정 전/후 두 번 호출했는데
  relevanceScore가 0.55 → 0.8로, 같은 이미지인데도 0.25나 차이 났다(프롬프트 변경 영향도 있지만
  SLEEP 항목 추가는 review_01과 무관한 문구라 대부분은 모델 자체의 응답 변동으로 추정). **단일 호출
  점수를 그대로 임계치 비교에 쓰는 건 노이즈에 취약**하다 — 프로덕션에서는 여러 번 호출해 평균을
  내거나, 임계치 근처 점수는 자동으로 REVIEW_REQUIRED로 보수적으로 처리하는 여유 구간(margin)을 두는
  것을 권한다.
- **라벨러 1인 기준**: 사람 라벨(pass/review/reject)이 어떤 기준으로 매겨졌는지 재현 가능한 문서가
  없어, 라벨 자체의 일관성(inter-rater reliability)은 검증하지 못했다.
- **duplicates 쌍 1개뿐**: 6절 참고, false positive 위험은 미검증.
- **reject_anomaly가 sleep에 없음**: sleep 카테고리에서 "조작된 수면 인증"이 실제로 어떻게 보이는지
  전혀 데이터가 없다.
- **이 보고서는 코드(STAGE7 규칙엔진)를 변경하지 않았다.** 4절/7절에서 발견한 "anomaly 심각도에 따른
  REJECT_CANDIDATE 격상" 같은 구조적 개선은 근거는 뚜렷하지만, 표본이 작아 규칙을 확정하기보다
  제안으로만 남겼다 — 실제 반영 여부는 확인 후 결정이 필요하다.

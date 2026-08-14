# 2026-08-14 세션 보고서 — 임계치 재조정 · 규칙엔진 재설계 · VIDEO 파이프라인 실측

이 문서는 하루 세션 동안 진행한 작업을 팀 공유용으로 정리한 것이다. 모든 수치는 `calibration-images/`
(정적 이미지, 45장)와 `test video/`(실제 .mp4 4개)를 **실제 STAGE6 Vision AI**(mock 아님, 실제
Anthropic API 호출, 모델 `claude-sonnet-5`)에 통과시켜 얻은 실측값이다.

재현용 도구:
- 이미지: `./gradlew runCalibration` (`ANTHROPIC_API_KEY` 필요)
- 영상: `./gradlew runVideoCalibration` (`ANTHROPIC_API_KEY` + PATH상의 `ffmpeg`/`ffprobe` 필요)

---

## 1. SLEEP 카테고리 기준 전환: 스크린샷 → 거울 셀카

기획 변경으로 "수면 인증"이 알람 스크린샷 방식에서 **아침 기상 거울 셀카** 방식으로 바뀌었다.

- `CalibrationRunner`의 sleep 카테고리 설명/기대 객체를 거울 셀카 기준으로 교체
- `VisionAnalysisPromptBuilder`의 SLEEP 전용 가드 문구 교체: "제출 시간창은 서버가 이미 검증했으니,
  이미지에 침대/잠옷/시계 같은 '기상' 증거가 없다는 이유만으로 relevanceScore를 깎지 말 것"

**효과 (실측, sleep pass 5장 relevanceScore)**

| | 수정 전 | 수정 후 |
|---|---|---|
| pass_01 | 0.35 | 0.8 |
| pass_02 | 0.4 | 0.75 |
| pass_03 | 0.75 | 0.9 |
| pass_04 | 0.4 | 0.85 |
| pass_05 | 0.4 | 0.85 |

수정 전에는 5장 중 4장이 relevance<0.5로 REVIEW에 잘못 라우팅됐으나, 수정 후 5장 전부 0.5 이상으로
올라와 정상 라우팅된다.

**후속 발견**: 거울 셀카 특성상 옷 텍스트가 좌우 반전되는데, Vision이 이걸 "거울 촬영 특성상 자연스러운
현상"이라고 스스로 서술하면서도 `anomalyFlags`에 넣는 오탐이 발견되어 프롬프트에 예외 문구를 추가했다
(모아레/워터마크 같은 진짜 증거는 그대로 유지되는 것을 확인).

---

## 2. BLUR_THRESHOLD 재조정: 100 → 20

`ImageQualityAnalyzer.BLUR_THRESHOLD`는 `isBlurry=true`가 되는 즉시 **제출이 하드 리젝**되는(운영자
검토 없이 "다시 촬영해주세요" 에러) 값이라 오탐의 비용이 크다.

**기존 100의 문제**: 사람이 정상(pass)으로 라벨한 사진 20장 중 4장(20%, 최저 blurScore=27.58)이
오탐으로 하드 리젝됐다.

**20으로 낮춘 뒤 검증**: 사용자가 추가한 "진짜 흔들린/초점 안 맞은" reject 샘플 4장(blurScore
1.4~2.84)을 실측한 결과, 4장 전부 정확히 차단됐다. 정상 사진 최저값(27.58)과 진짜 블러 최고값(2.84)
사이에 10배 가까운 여유가 있어 20이라는 값으로 명확하게 갈린다.

| 그룹 | n | blurScore 범위 | 20 기준 결과 |
|---|---|---|---|
| pass(정상) | 20 | 27.58 ~ 4653 | 전부 통과 |
| 진짜 블러(신규 샘플) | 4 | 1.40 ~ 2.84 | 전부 차단 |

해상도 임계값(480x480)은 거울 셀카 전환 이후 저해상도 오탐 자체가 사라져 별도 조정이 필요 없었다.

---

## 3. STAGE7 규칙엔진 재설계: "러프한 AI + 상호신고"

기존 규칙엔진은 관련성 점수(relevanceScore)·구도(isFramedProperly)까지 판단에 반영해 REVIEW로
보냈으나, **AI는 확실한 이상 신호(중복/블러/카테고리 불일치/조작 증거)만 러프하게 걸러내고, 애매한
판단은 참여자 상호신고에 위임**하는 방향으로 정책이 바뀌었다.

**변경 전**
```
0) 화질 게이트 실패            -> 하드 리젝
1) 중복                        -> REJECT_CANDIDATE
2) Vision 실패                 -> REVIEW_REQUIRED
3) objectPresence=false        -> REJECT_CANDIDATE
4) 이상징후 또는 관련성 애매    -> REVIEW_REQUIRED   ← 삭제
   또는 구도 이상
5) 그 외                       -> PASS
```

**변경 후**
```
0) 화질 게이트 실패            -> 하드 리젝
1) 중복                        -> REJECT_CANDIDATE
2) Vision 실패                 -> REVIEW_REQUIRED
3) objectPresence=false        -> REJECT_CANDIDATE
4) 이상징후(anomalyFlags)      -> REJECT_CANDIDATE   (REVIEW에서 격상)
5) 그 외                       -> PASS
```

relevanceScore·isFramedProperly는 계속 수집·저장하되(추후 신고 발생 시 운영자 참고 자료), 자동 판정
게이트로는 쓰지 않는다.

### 3-1. 부작용 발견 및 수정: anomalyFlags 심증성 추측 문제

규칙 변경 직후 재검증한 결과, skincare pass 사진 4장이 REJECT_CANDIDATE(HIGH)로 격상되는 부작용이
나타났다. 원인은 anomalyFlags에 "광고/화보 같아 보인다", "스튜디오 조명 같다"처럼 **화질이 좋다는
이유만으로 진위를 의심하는 심증성 추측**이 섞여 있었기 때문이다. (모아레 패턴, 타 플랫폼 워터마크 같은
구체적 증거와는 질이 다르다.)

`VisionAnalysisPromptBuilder`의 anomalyFlags 지시문에 "구체적 증거가 있을 때만 채울 것, 심증성 추측은
절대 포함하지 말 것"을 명시해 수정했다.

**효과 (실측, skincare pass 4장)**

| | 수정 전 | 수정 후 |
|---|---|---|
| pass_01~04 | anomaly 있음 → REJECT_CANDIDATE | anomaly 없음 → PASS |

동시에 `reject_anomaly_01`(진짜 모아레/스캔라인 패턴)은 수정 후에도 그대로 REJECT_CANDIDATE를
유지해, 진짜 증거는 놓치지 않으면서 심증만 걸러냈음을 확인했다.

---

## 4. 최종 캘리브레이션 정확도 (45장 전체 재검증)

모든 수정을 반영한 뒤 skincare/meal/exercise/sleep 45장을 한 번에 재실행한 결과다. "정확도"는
`AUTO_VALID` 여부가 사람 라벨과 일치하는지를 기준으로 한다.

| 사람 라벨 | n | 결과 | 비고 |
|---|---|---|---|
| pass | 20 | **19장(95%) 정확히 AUTO_VALID** | sleep pass_02 1장만 REJECT — 실제 타 SNS 워터마크가 찍혀있어 정당한 격상 |
| reject (중복·블러·무관·조작 전체) | 12 | **12장(100%) 전부 안전하게 차단** | anomaly 라벨도 이제 정확히 REJECT_CANDIDATE 도달(기존엔 REVIEW에 머묾) |
| review | 12 | 5장(42%)만 AI가 잡아냄, 7장(58%)은 자동 통과 | **의도된 결과** — 애매한 판단은 상호신고로 위임하기로 한 정책 반영 |

**종합 이진 정확도(AUTO_VALID 여부): 36/44 = 82%** (세션 시작 시점 68% 대비 개선). pass/reject는
사실상 완성 단계이며, review 라우팅 감소는 버그가 아니라 3절의 정책 변경에 따른 의도된 결과다.

---

## 5. VIDEO 파이프라인 실측 (STAGE4 최초 검증)

`test video/`의 실제 .mp4 4개(카테고리당 1개, 전부 "정상 영상"으로 가정)를 STAGE4(ffmpeg 프레임
추출)부터 STAGE7(규칙엔진)까지 실제 컴포넌트로 통과시켰다. 이 환경에 ffmpeg가 없어 winget으로
설치 후 진행했다.

### 5-1. 프로덕션 버그 발견: ffmpeg 파이프 데드락

`FfmpegVideoFrameExtractor`가 ffmpeg의 stdout/stderr를 전혀 읽지 않고 있었다. ffmpeg의 콘솔 출력
(코덱 배너 등)이 OS 파이프 버퍼를 채우면 ffmpeg가 write()에서 블록되고, Java 쪽 `waitFor()`가 15초
타임아웃날 때까지 끝나지 않는 구조였다. 실측 결과 4개 영상 전부, 3회 재시도 모두 타임아웃났다.

**중요도**: ffmpeg만 설치되면 될 줄 알았으나, 실제로는 **이 버그 때문에 VIDEO 제출이 지금까지 단
한 번도 성공할 수 없는 상태**였다. `ProcessBuilder`의 stdout/stderr를 `Redirect.DISCARD`로 버리도록
수정해 해결했다.

### 5-2. 설계 개선: 긴 영상에서의 단일 프레임 샘플링 한계

수정 후 재실행한 결과, skincare 영상(25.64초)만 REJECT_CANDIDATE로 나왔다. STAGE4가 뽑은 중간 지점
(12.8초) 프레임을 직접 열어 확인한 결과, 실제로 스킨케어 제품이나 동작 없이 카메라를 보고만 있는
구간이었다 — Vision의 판단 자체는 그 프레임만 놓고 보면 정확했다.

**원인**: `RoutineVerificationFrameCaptureService`의 mid/front/back 3개 후보 지점은 **ffmpeg 추출
실패 시에만** 재시도하고, "추출은 됐지만 그 순간에 루틴 증거가 없는" 경우는 재시도하지 않았다.

**대응**:
1. (기획) 앱에서 촬영 가능한 영상 길이를 5초로 제한하기로 결정 — "죽은 시간" 자체를 줄여 근본
   위험도를 낮춘다.
2. (코드) `captureAllCandidateFrames()`를 신규 추가해 3개 후보 프레임을 전부 추출하고,
   `objectPresence=false`이면서 **이상징후가 없는** 경우에만 다음 프레임으로 넘어가도록 재시도 조건을
   추가했다. ⚠️ 이상징후(anomalyFlags)가 있는 REJECT_CANDIDATE는 절대 재시도하지 않는다 — 조작 증거를
   한 프레임에서 찾았는데 다른 프레임이 우연히 깨끗하다고 통과시키면 조작 영상이 "운 좋은 프레임"만
   걸리는 우회로가 생기기 때문이다.

**재검증 결과**

| 영상 | 길이 | 결과 |
|---|---|---|
| exercise_test.mp4 | 4.6초 | 1번 프레임에서 바로 PASS |
| meal_test.mp4 | 9.4초 | 1번 프레임에서 바로 PASS |
| sleep_test.mp4 | 5.0초 | 1번 프레임에서 바로 PASS |
| skincare_test.mp4 | 25.6초 | 1번 프레임 objectPresence=false → 2번 프레임(1/4 지점)에서 제품 도포 확인 → **PASS** |

4개 영상 전부 "정상 영상" 가정과 일치하는 결과를 확인했다. 짧은 영상은 추가 Vision 호출 없이
1프레임으로 끝나 비용 낭비가 없고, 긴 영상만 필요한 만큼 재시도한다.

**참고**: `RoutineVerificationFrameCaptureService.captureAllCandidateFrames()`와 재시도 오케스트레이션은
아직 프로덕션 VIDEO 제출 파이프라인에 배선되지 않은 상태다(VIDEO는 여전히 보수적으로 REVIEW_REQUIRED
처리). 실제 배선 시 이 재시도 로직을 그대로 재사용하면 된다.

---

## 6. 변경된 파일 목록

- `src/main/java/com/allog/allogbe/routineverification/media/ImageQualityAnalyzer.java` — BLUR_THRESHOLD 100→20
- `src/main/java/com/allog/allogbe/routineverification/media/FfmpegVideoFrameExtractor.java` — 파이프 데드락 수정
- `src/main/java/com/allog/allogbe/routineverification/media/RoutineVerificationFrameCaptureService.java` — `captureAllCandidateFrames()` 추가
- `src/main/java/com/allog/allogbe/routineverification/vision/VisionAnalysisPromptBuilder.java` — SLEEP 가드 재작성, anomalyFlags 지시문 강화
- `src/main/java/com/allog/allogbe/routineverification/classification/RoutineVerificationClassificationRuleEngine.java` — 규칙엔진 재설계(relevance/framing 게이트 제거, anomaly 격상)
- `src/main/java/com/allog/allogbe/tools/calibration/CalibrationRunner.java` — sleep 카테고리 설정 갱신, `CATEGORY_CONFIGS` 공유용으로 가시성 조정
- `src/main/java/com/allog/allogbe/tools/calibration/VideoCalibrationRunner.java` — 신규, VIDEO 파이프라인 진단 도구
- `build.gradle` — `runVideoCalibration` 태스크 추가
- 관련 테스트: `ImageQualityAnalyzerTest`, `VisionAnalysisPromptBuilderTest`, `RoutineVerificationClassificationRuleEngineTest`, `RoutineVerificationClassificationPipelineTest`, `RoutineVerificationEndToEndTest`, `RoutineVerificationFrameCaptureServiceTest`

테스트 91개 전부 통과 상태다.

---

## 7. 남은 이슈 / 다음 단계

- **VIDEO 파이프라인 미배선**: `captureAllCandidateFrames()` 기반 재시도 로직을 실제
  `RoutineVerificationClassificationPipeline`(또는 VIDEO 전용 오케스트레이션)에 연결하는 작업이 남아있다.
- **영상 길이 5초 제한**: 기획 결정만 됐고 앱/서버단 강제(업로드 시 길이 검증 등) 구현은 아직 없다.
- **blur 임계값 20의 상한 미검증**: 정상 사진과 진짜 블러 사이 여유는 확인했지만, "임계값을 더
  낮춰도 되는지"(즉 review류의 근접샷·저조도 사진까지 통과시켜야 하는지)는 별도 정책 판단이 필요하다.
- **review 라우팅 42%**: 낮아진 게 의도된 결과이긴 하나, 실제 운영에서 상호신고가 이 몫을 얼마나
  잘 받아내는지는 별도로 모니터링이 필요하다.
- **에러 타입 분류/ReviewStatus 매핑**: AI↔백엔드 계약 합의가 아직 안 끝나 착수하지 않았다
  (`docs/AI_INTEGRATION_CONTRACT.md` 참고).

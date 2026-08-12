# 영상 인증 AI 개발 — 루틴 인증 1차 분석 에이전트

ALLOG의 5개 AI 접점 중 **③ 인증 1차 분석(Vision AI)** 전용 작업 폴더입니다. 저장소 루트가 아직
프레임워크 초기화 전이라, `비서 ai 개발/`과 동일한 방식으로 독립된 Gradle/Spring Boot 프로젝트로
이 폴더 안에 완결시켰습니다. 이후 루트 프로젝트가 초기화되면 `src/` 트리를 그대로 이식하면 됩니다.

## 무엇을 하는가

챌린지 루틴 수행 인증(사진/영상/앱기록) 제출을 받아 ① 객체 존재 ② 관련성 ③ 중복 ④ 제출시간
⑤ 이상징후 축으로 1차 분류합니다. **AI는 최종 인증 여부를 확정하지 않습니다** — `AUTO_VALID` /
`FLAGGED_FOR_REVIEW`까지만 자동 전환되며, `VALID_CONFIRMED`/`INVALIDATED`/`RESUBMIT_REQUESTED`는
운영자 전용 API(`PATCH /api/v1/admin/verifications/{id}`)에서만 확정됩니다.

## 실행

Java 21 필요.

```bash
cd "ai 개발/영상 인증 ai 개발"
./gradlew test
./gradlew bootRun
```

## 상세 문서

전체 설계, API 명세, 테스트 결과, 미정 사항, 리스크는 [`docs/ROUTINE_VERIFICATION_REPORT.md`](docs/ROUTINE_VERIFICATION_REPORT.md) 참고.

**요약**: 77/77 테스트 통과(실제 Spring 컨텍스트 + H2 기반 E2E 포함). Challenge/User/Report 도메인은
아직 없어 포트 인터페이스만 정의해두었고(연동 필요 지점), ①②④⑤ AI 기능은 스코프 밖이라 구현하지
않았습니다.

# Contributing

ALLOG 백엔드 저장소는 `develop` 브랜치를 기준으로 기능 브랜치를 만들어 작업합니다.

## 브랜치 전략

```text
main
└── develop
    ├── feature/*
    ├── fix/*
    ├── refactor/*
    ├── test/*
    └── docs/*
```

브랜치 이름 예시:

- `feature/auth-api`
- `feature/ranking-api`
- `fix/token-expiration`
- `refactor/challenge-service`
- `docs/api-spec`

저장소가 이미 백엔드 전용이므로 `feature/backend-auth-api`처럼 영역 이름을 반복하지 않습니다.

## 커밋 컨벤션

- `feat`: 새로운 기능
- `fix`: 버그 수정
- `docs`: 문서 수정
- `style`: 코드 포맷 변경
- `refactor`: 리팩터링
- `test`: 테스트 추가 또는 수정
- `chore`: 설정 및 기타 작업

## Pull Request 규칙

1. 기능별 브랜치에서 작업합니다.
2. `develop` 브랜치로 Pull Request를 생성합니다.
3. 자기 자신이 작성한 PR도 변경 내용을 직접 검토합니다.
4. 가능한 경우 최소 1명의 리뷰를 받은 후 병합합니다.
5. API 변경 사항은 PR 본문에 반드시 작성합니다.
6. 테스트하지 않은 기능을 테스트 완료로 표시하지 않습니다.
7. `main`에는 직접 푸시하지 않습니다.
8. 배포 가능한 버전만 `develop`에서 `main`으로 병합합니다.

## 보안 규칙

- `.env`, API Key, 비밀번호 등 비밀정보를 커밋하지 않습니다.
- 로컬 설정은 `.env`에 두고, 공유 가능한 키 이름만 `.env.example`에 작성합니다.

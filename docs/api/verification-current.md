# Current Verification API

모든 endpoint는 Firebase 인증으로 생성된 `AllogPrincipal`이 필요하다. Client가 보낸 `userId`, member ID, Firebase UID는 인증 정보로 사용하지 않는다.

## Android 호출 순서

1. `POST /api/v1/me/groups/{groupId}/verifications/current`
2. `POST /api/v1/me/groups/{groupId}/verifications/current/upload-intent`
3. 응답의 `requiredHeaders`를 그대로 적용해 `uploadUrl`로 raw media를 `PUT`
4. signed temporary PUT 성공 후 `POST /api/v1/me/groups/{groupId}/verifications/current/submit`

Android가 PUT 성공을 주장하더라도 Backend는 bound private object를 `VerificationMediaStorage.inspect()`로 다시 확인한다. Signed PUT은 nginx와 Spring Boot를 거쳐 private storage로 streaming되며 public static serving을 사용하지 않는다.

## Current slot

```http
POST /api/v1/me/groups/{groupId}/verifications/current
```

```json
{
  "verificationId": 123,
  "scheduledDate": "2026-08-13",
  "status": "PENDING_UPLOAD",
  "submissionDeadline": "2026-08-13T13:00:00Z"
}
```

Create-or-get이므로 신규/기존 모두 `200 OK`다. 기존 상태와 Backend deadline을 그대로 반환한다.

## Upload intent

```http
POST /api/v1/me/groups/{groupId}/verifications/current/upload-intent
Content-Type: application/json
```

```json
{
  "contentType": "video/mp4",
  "sizeBytes": 12345678
}
```

```json
{
  "method": "PUT",
  "uploadUrl": "temporary signed upload URL",
  "requiredHeaders": {
    "content-type": ["video/mp4"],
    "if-none-match": ["*"],
    "x-allog-upload-signature": ["…"]
  },
  "expiresAt": "2026-08-13T12:55:00Z"
}
```

응답은 `Cache-Control: no-store`다. `requiredHeaders`의 모든 값을 signed PUT에 그대로 적용해야 한다. `uploadUrl`과 signature는 임시 credential이므로 Android와 Backend 모두 전체 URL을 log에 남기지 않는다. Object key와 private storage path는 public contract가 아니다. Signed PUT URL 자체는 grant가 security boundary이므로 Firebase bearer token을 요구하지 않는다.

## Submit

```http
POST /api/v1/me/groups/{groupId}/verifications/current/submit
```

```json
{
  "verificationId": 123,
  "scheduledDate": "2026-08-13",
  "status": "SUBMITTED",
  "submittedAt": "2026-08-13T12:54:30Z"
}
```

이미 제출된 Verification은 `200 OK`로 현재 persisted status와 최초 `submittedAt`을 반환한다. signed PUT 실패 시 submit을 호출하지 않는 것이 정상 UX이며, 호출하더라도 Backend는 `409 Conflict`로 거부한다.

`SUBMITTED`는 Backend가 deadline 안에 bound media의 존재와 metadata를 확인해 접수했다는 뜻이다. 영상 내용이 루틴을 충족했다거나 AI가 승인했다는 뜻이 아니며, `APPROVED` 판정과 Video AI/운영 검토 연동은 다른 팀과 후속 단계의 책임이다. 이 API는 `APPROVED` 상태를 만들지 않는다.

## Error status

| Status | 의미 |
|---:|---|
| 400 | 잘못된 group ID, JSON, 빈 MIME, 0 이하 크기 |
| 401 | 인증 없음/실패 |
| 404 | membership 없음, LEFT/REMOVED, 다른 사용자 group |
| 409 | lifecycle/deadline 충돌, binding/object 없음, metadata 불일치 |
| 413 | 설정된 최대 media 크기 초과 |
| 415 | 허용되지 않은 MIME 또는 stored MIME 불일치 |
| 500 | Backend/storage configuration 또는 invariant 오류 |
| 503 | media storage 비활성/일시 장애 |

오류 응답 body는 비어 있으며 내부 ID, object key, private storage path, 예외 message를 반환하지 않는다.

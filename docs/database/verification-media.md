# Verification Media Storage

이 문서는 Verification media storage의 MVP Backend 계약이다. Product의 MIME, 파일 크기, 업로드 만료, 보관 기간은 확정하지 않는다.

## 경계

```text
Verification
→ unique VerificationMedia
→ private Gabia local filesystem object
```

- `VerificationMediaStorage`가 storage 구현을 숨긴다. Domain/service layer는 local path나 filesystem API를 알지 못한다.
- Object key는 `verification-media/{random UUID}` 형식으로 Backend가 생성한다. Client filename, login ID, 이름, user ID는 key에 사용하지 않는다.
- Android는 authenticated `upload-intent`가 돌려준 `uploadUrl`, `method`, `requiredHeaders`, `expiresAt`만 사용한다. Android는 object key, local path, signing secret, AWS credential을 받지 않는다.
- Upload URL은 `https://api.allog-app.store`의 signed temporary `PUT`이다. 요청은 nginx와 Spring Boot를 거쳐 private filesystem에 저장된다.
- Signed grant는 method, opaque id, object key, normalized Content-Type, expected size, expiry를 HMAC-SHA256으로 binding한다. `If-None-Match: *`와 one-time grant consumption으로 public PUT의 overwrite/replay를 차단한다.
- Backend는 `inspect()`로 binding, actual size, stored content type을 확인한 뒤에만 media confirmation과 `SUBMITTED`를 DB transaction에 반영한다. Client upload success는 제출 증거가 아니다.
- Storage I/O는 pessimistic DB transaction 밖에서 수행한다.

## Private filesystem 운영 요구사항

- Media root는 nginx static content나 public `alias`가 아니어야 한다.
- Production root `/var/lib/allog/verification-media`는 deployment preflight에서 `allog:allog`, mode `0750`으로 생성한다. Application은 root ownership을 변경하지 않는다.
- Final object와 metadata는 private permissions로 저장된다. filesystem symlink를 만들 수 있는 주체는 trusted operator/service account로 제한한다.
- nginx upload route의 `client_max_body_size`는 승인된 `VERIFICATION_MEDIA_MAX_BYTES`와 일치하도록 route-specific으로 설정한다. 값이 확정되기 전에는 숫자를 임의로 설정하지 않는다.
- Disk capacity, backup/durability, orphan recovery와 retention/delete 기간은 운영 책임이다. local media는 single-host durability를 가진다.
- In-memory grants는 single application instance를 전제로 한다. process restart는 outstanding grant를 무효화한다.

## 설정

Media 기능은 기본 비활성화다. 활성화할 때 다음 값을 모두 environment로 명시한다.

```text
VERIFICATION_MEDIA_ENABLED=true
VERIFICATION_MEDIA_LOCAL_ROOT
VERIFICATION_MEDIA_LOCAL_BASE_URL
VERIFICATION_MEDIA_LOCAL_SIGNING_SECRET
VERIFICATION_MEDIA_MAX_BYTES
VERIFICATION_MEDIA_UPLOAD_EXPIRY
VERIFICATION_MEDIA_ALLOWED_CONTENT_TYPES
```

`VERIFICATION_MEDIA_LOCAL_BASE_URL`은 HTTPS public API URL이다. signing secret은 server-only이며 Git, Android, API response와 application log에 포함하지 않는다. `VERIFICATION_MEDIA_ALLOWED_CONTENT_TYPES`는 explicit MIME을 쉼표로 구분하며 wildcard/parameter는 허용하지 않는다. 실제 product allow-list와 file size/expiry 값은 별도 Product Decision이다.

## 미구현 정책

- retention/delete 기간과 orphan cleanup lifecycle
- multi-instance shared grant store
- Video AI 모델 및 callback

Downstream image sanitization/decoder가 실제 image parsing을 fail-closed한다. 향후 provider는 public media URL이 아니라 Backend storage boundary를 통해 private media를 읽어야 한다.

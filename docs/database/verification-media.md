# Verification Media Storage

이 문서는 Verification media storage의 MVP Backend 계약이다. Product의 MIME, 파일 크기, 보관 기간을 확정하지 않는다.

## 경계

```text
Verification
→ unique VerificationMedia
→ private S3 object
```

- Android는 Backend가 발급한 presigned `PUT`으로만 업로드한다.
- Object key는 `verification-media/{random UUID}` 형식으로 Backend가 생성한다.
- Client filename, email, 이름, Firebase UID를 object key에 사용하지 않는다.
- Client가 object key 또는 업로드 성공 여부를 제출 증거로 전달하지 않는다.
- Backend는 `HeadObject`로 binding, 실제 크기, Content-Type을 확인한 후에만 media confirmation과 `SUBMITTED`를 같은 DB transaction에서 반영한다.
- S3 network 호출은 pessimistic DB transaction 밖에서 수행한다.

## Bucket/IAM 운영 요구사항

- Bucket과 object는 private이며 S3 Block Public Access를 활성화한다.
- Presigned upload는 `Content-Type`과 `If-None-Match: *`를 서명한다.
- Bucket policy에서도 conditional write를 강제해 동일 key overwrite를 막는다.
- Backend는 AWS default credential provider chain과 EC2 IAM role을 사용한다.
- IAM은 지정 bucket/prefix의 `s3:PutObject`, `s3:GetObject`와 missing object를 404로 구분하는 데 필요한 prefix-scoped `s3:ListBucket`만 부여한다.
- Android에는 AWS 장기 credential과 Delete 권한을 제공하지 않는다.
- Presigned URL 전체, AWS credential, media binary를 application log에 남기지 않는다.

Bucket policy/IaC 자체는 이 repository의 현재 범위가 아니다.

## 설정

Media 기능은 기본 비활성화다. 활성화할 때 다음 값을 모두 명시한다.

```text
VERIFICATION_MEDIA_ENABLED=true
AWS_REGION
VERIFICATION_MEDIA_BUCKET
VERIFICATION_MEDIA_MAX_BYTES
VERIFICATION_MEDIA_UPLOAD_EXPIRY
VERIFICATION_MEDIA_ALLOWED_CONTENT_TYPES
```

`VERIFICATION_MEDIA_ALLOWED_CONTENT_TYPES`는 `video/mp4,image/jpeg`처럼 explicit MIME을 쉼표로 구분한다. Wildcard는 허용하지 않는다. 예시는 테스트 fixture일 뿐 Product allow-list가 아니다.

## 미구현 정책

- 실제 binary type/magic-byte 검사
- multipart upload
- retry attempt history
- orphan cleanup scheduler
- retention/delete 기간
- Video AI 모델 및 callback

향후 Video AI는 public URL이 아니라 Backend IAM read 또는 짧은 signed read URL을 통해 private object를 읽는다.

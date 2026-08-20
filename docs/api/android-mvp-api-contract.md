# ALLOG Android MVP API Contract

Extracted from the production code on this branch. Where this document and the code disagree, the
code wins — every field below was read out of a controller or DTO, not from a design note.

## Base

- **Base path**: `/api/v1`
- **Content-Type**: `application/json` (request and response)
- **Auth**: `Authorization: Bearer <ALLOG Access Token>` on every endpoint below
- **Dates/times**: `LocalDate` → `"2026-08-16"`, `LocalTime` → `"23:00:00"`, `Instant` → ISO-8601 UTC
- **Enums on the wire**: group/verification enums are **UPPERCASE** (`RECRUITING`, `APPROVED`).
  Profile and onboarding enums are **lower_snake_case** (`female`, `fact_based`, `hydration`) — this
  asymmetry is real, do not normalise one to the other.

### Error bodies

Most endpoints answer with **status only and no body**. Two exceptions:

| Shape | Where |
|---|---|
| `{"error":{"code","message","details":[{"field","reason"}]}}` | profile + stats endpoints |
| `{"code":"INSUFFICIENT_HEARTS"}` | group join / create / invite-join, on 409 |

Treat "409 with no body" and "409 with a code" as different cases in the client.

---

## Auth

ALLOG access token in `Authorization: Bearer …`. The backend verifies its signature and expiry, then
maps its numeric subject to the internal user.

- Missing/invalid/expired token → **401** (empty body)
- `POST /api/v1/auth/signup` creates a lowercase local login ID and returns an access token
- `POST /api/v1/auth/login` verifies the BCrypt password and returns an access token
- Auth endpoints and `POST /api/v1/dev/ai-coach/preview` are unauthenticated

**ANDROID NOTES**: a fresh local account is authenticated but has **no profile**. `GET /users/me`
answers 404 until onboarding completes — that 404 is the "show onboarding" signal, not an error.
Local startup requires `ALLOG_AUTH_TOKEN_SECRET` with at least 32 bytes.

---

## Profile

### `GET /api/v1/users/me` — read profile
**AUTH** required · **200** / **404** `PROFILE_NOT_FOUND` when onboarding is not done
```json
{ "userId": 1, "nickname": "민지", "gender": "female", "birthDate": "2000-07-30",
  "onboarding": { "interestRoutines": ["hydration","exercise"], "coachStyle": "supportive",
    "averageSleepHours": 7.0, "exerciseDaysPerWeek": 3, "mealsPerDay": 3,
    "preferredGroupDurationDays": 7 } }
```
`gender` and `birthDate` are nullable. There is **no** `email`, `heightCm`, `weightKg`,
`profileImageUrl`, or `stats` field — do not bind them.

### `POST /api/v1/users` — create profile + onboarding
**AUTH** required · **201** returns the same body as `GET /users/me`

**This does not create a user** — authentication already did. It creates the profile and onboarding
for the signed-in user. No user id is sent in the body.

| Field | Type | Required | Values |
|---|---|---|---|
| `nickname` | string | yes | trimmed, 1–20 chars, not unique |
| `gender` | string | no | `female`, `male` |
| `birthDate` | date | no | not in the future (Asia/Seoul) |
| `onboarding.interestRoutines` | string[] | yes | ≥1, **no duplicates**: `hydration`, `exercise`, `meal`, `sleep`, `skincare` |
| `onboarding.coachStyle` | string | yes | `supportive`, `pressuring`, `fact_based`, `humorous` |
| `onboarding.averageSleepHours` | number | yes | 0–24, at most 1 decimal |
| `onboarding.exerciseDaysPerWeek` | int | yes | 0–7 |
| `onboarding.mealsPerDay` | int | yes | 0–10 |
| `onboarding.preferredGroupDurationDays` | int | yes | 7, 14, or 30 |

**Errors**: 400 `VALIDATION_ERROR` · 400 `UNKNOWN_FIELD` (any property not listed above) ·
**409 `PROFILE_ALREADY_EXISTS`**

**ANDROID NOTES**: creating the profile also opens the heart wallet with **3 hearts**, in the same
transaction. Send only the fields above — an extra key is a 400, not ignored.

### `PATCH /api/v1/users/me` — partial update
**AUTH** required · **200** returns the full profile

**Absent ≠ null.** Omitting a field leaves it alone; sending `null` clears it where clearing is
allowed:

| Field | omit | `null` |
|---|---|---|
| `nickname` | unchanged | **400** |
| `gender`, `birthDate` | unchanged | cleared |
| `onboarding` and every field inside it | unchanged | **400** |

`{}` is a valid no-op. Nested: `{"onboarding":{"preferredGroupDurationDays":14}}` changes only that.

---

## User Stats

### `GET /api/v1/users/me/stats`
**AUTH** required · **200** · **404** `PROFILE_NOT_FOUND` without a profile
```json
{ "hearts": 3, "rewardPoints": 40, "successfulRoutines": 2 }
```
- `hearts` (int) — spendable balance
- `rewardPoints` (long) — sum of verification reward points; **includes** rewards from later
  invalidated verifications (no clawback policy exists). Not a spendable currency.
- `successfulRoutines` (long) — memberships that finished as `COMPLETED`

---

## Public Explore

### `GET /api/v1/groups`
**AUTH** required · **200** · returns **PUBLIC + RECRUITING only**, newest first, **not paginated**
```json
{ "items": [ {
  "groupId": 12, "name": "아침 물 마시기",
  "routine": { "routineDefinitionId": 3, "name": "물 마시기", "description": null },
  "status": "RECRUITING", "visibility": "PUBLIC",
  "maxMembers": 5, "currentMembers": 2, "requiredCompletionCount": 3,
  "schedule": { "scheduleType": "DAILY", "startDate": "2026-08-20", "endDate": "2026-08-27",
    "deadlineTime": "23:00:00", "timezone": "Asia/Seoul", "specificDays": [] } } ] }
```
`currentMembers` counts `JOINED` members. Private groups never appear here.

---

## Group Create

### `POST /api/v1/me/groups`
**AUTH** required · **201** → `{ "groupId": 12 }`
```json
{ "routineDefinitionId": 3, "name": "아침 물 마시기", "visibility": "PUBLIC",
  "maxMembers": 5, "requiredCompletionCount": 3,
  "verificationTemplateKey": "MEAL_PHOTO_RECORD",
  "schedule": { "scheduleType": "DAILY", "startDate": "2026-08-20", "endDate": "2026-08-27",
    "deadlineTime": "23:00:00", "timezone": "Asia/Seoul", "specificDays": [] } }
```
- `visibility`: `PUBLIC` | `PRIVATE`
- `scheduleType`: `DAILY` | `WEEKLY`; `specificDays` is `["MONDAY",…]`, may be omitted for daily
- `verificationTemplateKey`: optional; `null` = record-only group. The only approved key today is
  `MEAL_PHOTO_RECORD` — any other value is rejected.

**Errors**: 400 (validation, unknown template, `requiredCompletionCount` larger than the schedule
can hold) · 404 (routine definition not found) · **409 `{"code":"INSUFFICIENT_HEARTS"}`**

**ANDROID NOTES**: the creator joins their own group as `OWNER` and **pays 1 heart**, so a room of
`maxMembers: 1` is instantly full and starts immediately.

---

## Public Join

### `POST /api/v1/groups/{groupId}/join`
**AUTH** required · no body · **204 No Content**

Costs **1 heart**. Filling the last slot **starts the group in the same transaction** — every member
becomes `ACTIVE` at one shared instant.

| Status | Meaning |
|---|---|
| 204 | joined (and possibly started the group) |
| 404 | group not found |
| 409 `{"code":"INSUFFICIENT_HEARTS"}` | not enough hearts |
| 409 (no body) | already joined, not joinable, full, **or private group (invite required)** |

**ANDROID NOTES**: a plain 409 is ambiguous by design; re-fetch the group to tell the cases apart.
A **PRIVATE** group cannot be joined here — use the invite flow.

---

## Private Invite

### `POST /api/v1/me/groups/{groupId}/invite` — owner issues a code
**AUTH** required · no body · **200** → `{ "code": "…" }`

**Errors**: 404 (group not found / not yours) · 409 (group is not PRIVATE)

### `POST /api/v1/groups/join-by-invite` — join with a code
**AUTH** required · `{ "code": "…" }` (non-blank, ≤32 chars) · **204 No Content**

| Status | Meaning |
|---|---|
| 204 | joined |
| 404 | invite not found / group not found |
| 409 `{"code":"INSUFFICIENT_HEARTS"}` | not enough hearts |
| 409 (no body) | already joined, not joinable, full |

Costs **1 heart**, same as a public join.

---

## My Groups

### `GET /api/v1/me/groups?page=0&size=20`
**AUTH** required · **200** · `page` ≥ 0, `size` 1–50
```json
{ "items": [ { "groupId": 12, "groupName": "아침 물 마시기", "visibility": "PUBLIC",
    "groupStatus": "ACTIVE", "routineName": "물 마시기",
    "myRole": "OWNER", "myStatus": "ACTIVE" } ],
  "page": 0, "size": 20, "hasNext": false }
```

### `GET /api/v1/me/groups/{groupId}`
**AUTH** required · **200** · **404** if you are not a member (private groups do not leak)
```json
{ "group": { "groupId": 12, "name": "…", "visibility": "PUBLIC", "status": "ACTIVE",
    "maxMembers": 5, "requiredCompletionCount": 3 },
  "routine": { "name": "물 마시기", "description": null },
  "schedule": { "scheduleType": "DAILY", "startDate": "…", "endDate": "…",
    "deadlineTime": "23:00:00", "timezone": "Asia/Seoul", "specificDays": [] },
  "membership": { "myRole": "OWNER", "myStatus": "ACTIVE" } }
```
`schedule` may be `null`. **Enums**: `RoutineGroupStatus` = `DRAFT|RECRUITING|FULL|ACTIVE|COMPLETED|CANCELLED|EXPIRED`;
`GroupMemberStatus` = `JOINED|ACTIVE|COMPLETED|FAILED|LEFT|REMOVED`; `GroupMemberRole` = `OWNER|MEMBER`.

`COMPLETED` on a **group** means the run ended — not that everyone succeeded. A completed group can
hold both `COMPLETED` and `FAILED` members.

---

## Leave / Cancel

### `POST /api/v1/groups/{groupId}/leave` — member leaves before the start
**AUTH** required · no body · **204**

- Repeating it is a **204 no-op**, not an error
- 409 if you are the **owner** (owners cancel), or the group has already **started**
- 404 if the group or your membership does not exist

### `POST /api/v1/me/groups/{groupId}/cancel` — owner closes before the start
**AUTH** required · no body · **204**

- Repeating it is a **204 no-op**
- 404 if you are not the owner (a group you do not own is invisible under `/me`)
- 409 if the group has already started

**ANDROID NOTES**: the heart is **refunded automatically** by the backend on leave, cancellation and
expiry. There is no refund endpoint and the client must never try to trigger one. Leaving after the
group has started is **not supported** — that policy is undecided, so it is refused rather than
guessed.

---

## Verification

Base: `/api/v1/me/groups/{groupId}/verifications/current`

### `POST …/verifications/current` — get or create today's slot
Note this is a **POST**, not a GET — it creates the slot if today has none.
**200** →
```json
{ "verificationId": 55, "scheduledDate": "2026-08-16", "status": "PENDING_UPLOAD",
  "submissionDeadline": "2026-08-16T14:00:00Z" }
```
`VerificationStatus` = `PENDING_UPLOAD|SUBMITTED|PROCESSING|APPROVED|REVIEW_REQUIRED|RETRY_REQUIRED|REJECTED|INVALIDATED`

### `POST …/verifications/current/upload-intent` — signed temporary upload
`{ "contentType": "image/jpeg", "sizeBytes": 148985 }` → **200**
```json
{ "method": "PUT", "uploadUrl": "https://…", "requiredHeaders": { "Content-Type": ["image/jpeg"] },
  "expiresAt": "2026-08-16T12:10:00Z" }
```
Upload the bytes directly to `uploadUrl` with exactly the returned method and headers.

### `POST …/verifications/current/submit` — confirm the upload
no body → **200**
```json
{ "verificationId": 55, "scheduledDate": "2026-08-16", "status": "SUBMITTED",
  "submittedAt": "2026-08-16T12:05:00Z" }
```

**Errors**: 400 invalid size · 404 no membership/verification · 409 (media not uploaded, binding
mismatch, lifecycle conflict) · 413 too large · 415 unsupported/mismatched content type ·
**503 when media storage is unavailable or disabled**

**ANDROID NOTES**
- **PHOTO only**: `image/jpeg` and `image/png` are the sanitizable types. VIDEO and HEIC are **not**
  supported in this MVP.
- EXIF/GPS is stripped server-side before storage and before any AI call.
- ⚠️ **`VERIFICATION_MEDIA_ENABLED` defaults to `false`.** Until local verification media storage is configured, `upload-intent`
  and `submit` answer **503**. Creating the slot still works. See *Cloud Deployment* below.
- An AI or network failure is **never** recorded as the member failing — it stays pending for retry.

---

## Progress

### `GET /api/v1/me/groups/{groupId}/progress`
**AUTH** required · **200** · **404** if not a member
```json
{ "participationStatus": "ACTIVE",
  "personal": { "todayScheduled": true, "todayCompleted": false, "todayVerificationPending": false,
    "completedCount": 2, "requiredCompletionCount": 3, "currentStreak": 2, "previousBestStreak": 2,
    "remainingOpportunityCount": 4, "pendingDecisionCount": 0,
    "certificationDeadline": "2026-08-16T14:00:00Z" },
  "group": { "eligibleMemberCount": 2, "completedRequirementCount": 4, "totalRequiredCount": 6,
    "groupCompletionRate": 0.66, "pendingDecisionCount": 0, "goalAchievedMemberCount": 1 } }
```
**`personal` and `group` are `null` unless `participationStatus` is `ACTIVE`.** Before the group
starts, or after it ends, only `participationStatus` is populated — bind them as nullable.
`certificationDeadline` is nullable.

---

## AI Coach

### `GET /api/v1/groups/{groupId}/ai-coach`
**AUTH** required · **200** · **404** if not a member of the group
```json
{ "title": "…", "message": "…", "participationStatus": "ACTIVE",
  "insightType": "DEADLINE_APPROACHING", "routineState": "ATTENTION",
  "actionType": "OPEN_CERTIFICATION", "actionLabel": "인증하기", "generationType": "TEMPLATE",
  "suggestedQuestions": [
    { "id": "PACE_CHECK", "label": "지금 페이스 어때요?" },
    { "id": "NEXT_ACTION", "label": "지금 가장 중요한 건 뭐예요?" },
    { "id": "GROUP_PROGRESS", "label": "우리 그룹은 잘하고 있나요?" }
  ] }
```
`generationType` tells you whether the copy came from the model or the template fallback. The
service always answers — a provider failure degrades to a template rather than erroring.
`suggestedQuestions` contains these three backend-owned presets for `ACTIVE` participation and is
an empty array for every other lifecycle status.

### `POST /api/v1/groups/{groupId}/ai-coach/follow-up`
**AUTH** required · **200** · **404** if not a member of the group
```json
{ "questionId": "PACE_CHECK" }
```
The response has the same shape as the GET response above. `questionId` must be one of
`PACE_CHECK`, `NEXT_ACTION`, or `GROUP_PROGRESS`; missing or unknown values return **400**. The
client cannot send free-form question text. The selected id is converted to a backend-owned trusted
instruction and combined with current backend progress facts. Provider failure is still a **200**
with `generationType: "TEMPLATE"`; follow-ups are transient and are not persisted.

**Do not call** `POST /api/v1/dev/ai-coach/preview`: it is a dev-profile-only, unauthenticated
preview and is not present in a normal deployment.

---

## Cloud Deployment — deferred

These are configuration, not code, and are **not provisioned yet**:

| Feature | Env | Default | Effect on Android |
|---|---|---|---|
| Verification media (private local storage) | `VERIFICATION_MEDIA_ENABLED`, `VERIFICATION_MEDIA_LOCAL_ROOT`, `VERIFICATION_MEDIA_LOCAL_BASE_URL`, `VERIFICATION_MEDIA_LOCAL_SIGNING_SECRET`, `VERIFICATION_MEDIA_ALLOWED_CONTENT_TYPES` | `false` | `upload-intent` / `submit` → **503** |
| AI verification analysis | `VERIFICATION_ANALYSIS_ANTHROPIC_ENABLED`, `ANTHROPIC_API_KEY` | `false` | submitted verifications stay pending until an operator decides |
| AI coach copy | `OPENAI_API_KEY` | empty | coach answers with `generationType: TEMPLATE` |
| ALLOG auth | `ALLOG_AUTH_TOKEN_SECRET`, `ALLOG_AUTH_TOKEN_TTL` | no secret, `24h` | startup fails without a 32-byte secret |

Everything else works against the local profile today.

# RunningApp 백엔드

Node.js 24에서 실행합니다. SQLite 데이터는 기본적으로 `backend/data/runs.db`에 저장됩니다.

```powershell
node backend/server.mjs
```

서버는 저장소 루트에서 실행하며 `127.0.0.1:3000`에서 시작합니다.

## 가입과 로그인

가입과 로그인은 `Content-Type: application/json`으로 이메일과 비밀번호를 보냅니다.

```json
{
  "email": "runner@example.test",
  "password": "<15자 이상의 비밀번호>"
}
```

| API | 성공 응답 | 동작 |
| --- | --- | --- |
| `POST /auth/register` | `201`과 `{"user":{"id":"<계정 UUID>","email":"runner@example.test"}}` | 계정을 생성합니다. 가입 후 로그인으로 토큰을 발급받습니다. |
| `POST /auth/login` | `200`과 `accessToken`, `tokenType`, `expiresAt`, `user` | 세션 토큰을 발급합니다. `tokenType`은 `Bearer`이고 `expiresAt`은 UTC ISO 8601입니다. |
| `GET /auth/me` | `200`과 `{"user": ...}` | 현재 토큰의 계정 정보를 반환합니다. |
| `POST /auth/logout` | `204` | 요청에 사용된 토큰을 폐기합니다. |

이메일은 앞뒤 공백을 제거하고 소문자로 저장합니다. 가입 비밀번호는 15~128자이며 앞뒤 공백도 비밀번호의 일부입니다. 이메일과 비밀번호 형식이 맞지 않으면 `400 invalid_registration` 또는 `400 invalid_login`, 이미 등록된 이메일은 `409 email_unavailable`, 로그인 정보가 틀리면 `401 invalid_credentials`를 반환합니다. 가입·로그인 요청이 서버의 처리 한도를 넘으면 `429 too_many_requests`를 반환합니다.

앱은 로그인 응답의 토큰을 기기의 보안 저장소에 보관하고 `Authorization: Bearer <accessToken>`으로 전송합니다. 러닝, 초기 체력, 목표 API는 로그인 토큰에서 계정 ID를 확인합니다. 토큰은 7일 후 만료되며 로그아웃하거나 만료된 토큰으로 보호된 API를 호출하면 `401 unauthorized`를 반환합니다. 새로 로그인해도 계정 ID는 동일합니다.

오프라인 전송 대기 기록은 계정 ID별로 보관합니다. `401`을 받으면 기기 기록을 유지하고, 같은 계정으로 재로그인한 뒤 해당 계정의 기록을 재전송합니다.

비밀번호는 사용자별 salt와 scrypt 해시로 저장하고 세션 토큰은 SHA-256 해시로 저장합니다. 현재 구현은 로컬 개발과 앱 연동에 사용할 수 있습니다. 외부 배포에는 HTTPS와 이메일 소유 확인·계정 복구가 남아 있습니다.

기존 통합 테스트용 `RUN_API_TOKENS`는 선택 사항이며 `NODE_ENV=production`에서는 사용할 수 없습니다. 개발용 ID로 저장된 기록은 새 계정에 자동으로 연결되지 않습니다.

## 러닝 업로드

`POST /runs`에 `Authorization: Bearer <token>`과 `Content-Type: application/json`을 보냅니다.

| 요청 필드 | 타입 | 제약 |
| --- | --- | --- |
| `clientRunId` | 문자열 | 필수. 러닝마다 앱에서 발급한 고유 ID. 영문자, 숫자, `_`, `-`로 구성된 1~128자. UUID 권장. |
| `distanceMeters` | 정수 | 필수. 미터 단위, 0 이상. |
| `movingSeconds` | 정수 | 필수. 초 단위, 1 이상. |
| `type` | 문자열 | 필수. `free`, `assessment` 또는 `mission`. |
| `missionId` | 문자열 또는 null | `mission`이면 서버에서 시작한 미션의 ID가 필수입니다. 그 외에는 생략하거나 `null`을 보냅니다. |
| `finishedAt` | 문자열 또는 null | 실제 주행 종료 시각. UTC ISO 8601 형식으로 전송합니다. 예를 들어 `2026-11-27T06:30:00.000Z`입니다. 구버전 앱은 생략하거나 `null`을 보낼 수 있습니다. |

### 요청 예시

```json
{
  "clientRunId": "c45bbdbd-8481-4078-8104-ef4373ea8e21",
  "distanceMeters": 3000,
  "movingSeconds": 1260,
  "type": "assessment"
}
```

### 응답 예시

```json
{
  "id": "118a3c72-c42b-4d1b-afc7-ec6b7c6bb976",
  "clientRunId": "c45bbdbd-8481-4078-8104-ef4373ea8e21",
  "distanceMeters": 3000,
  "movingSeconds": 1260,
  "type": "assessment",
  "missionId": null
}
```

| 상태 | 의미 또는 오류 코드 | 앱 처리 |
| --- | --- | --- |
| `201 Created` | 새 기록 저장 | 서버 `id`를 저장하고 전송 대기를 해제합니다. |
| `200 OK` | 동일 기록 재전송. 최초 응답과 같은 서버 `id`를 반환합니다. | 전송 대기를 해제합니다. |
| `400 Bad Request` | `invalid_run` | 입력값을 확인합니다. |
| `401 Unauthorized` | `unauthorized` | 유효한 인증으로 다시 시도합니다. |
| `409 Conflict` | `client_run_id_conflict`. 같은 사용자의 같은 ID에 다른 값이 들어온 경우로 기존 기록을 유지합니다. | 자동 재시도를 중단하고 충돌을 확인합니다. |
| `413 Content Too Large` | `payload_too_large`. 본문 최대 8,192바이트. | 요청 크기를 확인합니다. |
| `415 Unsupported Media Type` | `expected_json` | JSON 헤더와 본문을 확인합니다. |
| `500 Internal Server Error` | `storage_error` | 기기 기록을 유지하고 나중에 다시 전송합니다. |

오류의 응답 형식은 `{"error":"invalid_run"}`과 같습니다. 앱은 시작 시 만든 `clientRunId`와 완료 시 확정한 데이터를 함께 저장하고, 연결 끊김·응답 유실·서버 오류 후에도 그대로 재전송합니다.

미션 주행의 `201`과 `200` 응답에는 `missionResult`가 추가됩니다. 여기에는 `outcome`(`success` 또는 `failed`), `failReason`, `paceSecondsPerKm`, `goalAchieved`, `feedback`이 포함됩니다. 미달성도 저장에 성공했다면 `201`입니다.

오프라인에서 주행을 마치면 종료 시각을 기기에 기록하고 재전송에도 같은 값을 보냅니다. 서버는 밀리초가 없는 UTC 시각에도 `.000`을 붙여 저장합니다.

## 러닝 기록 조회

`GET /runs?limit=20`은 Bearer 토큰에 해당하는 계정의 러닝 기록만 조회합니다. `limit`은 1~100이고 기본값은 20입니다. 응답은 `{"items":[...],"nextCursor":null}` 형식입니다. `nextCursor`에 값이 있으면 다음 요청의 `cursor`에 그대로 보냅니다. 잘못된 파라미터나 사용할 수 없는 커서는 `400 invalid_history_query`입니다.

각 항목에는 `id`, `clientRunId`, `distanceMeters`, `movingSeconds`, `type`, `missionId`, `finishedAt`, `averagePaceSecondsPerKm`, `missionResult`가 들어갑니다. 거리가 0이면 평균 페이스, 미션 결과가 없으면 `missionResult`는 `null`입니다.

실제 종료 시각이 최신인 기록부터 반환합니다. 시각이 같으면 서버 기록 ID로 정렬 순서를 고정합니다. 실제 종료 시각이 없는 과거 기록은 목록 뒤에 `finishedAt: null`로 표시됩니다.

## 주간·월간 러닝 통계

`GET /stats?period=week&date=2026-11-27`은 Bearer 토큰에 해당하는 계정의 기간별 합계를 반환합니다. `period`는 `week` 또는 `month`이며, `date`는 보고 싶은 기간에 속하는 날짜(`YYYY-MM-DD`)입니다. 생략하면 한국 시간의 오늘을 사용합니다. 잘못된 기간이나 날짜는 `400 invalid_stats_query`를 반환합니다.

```json
{
  "period": "week",
  "timeZone": "Asia/Seoul",
  "startDate": "2026-11-23",
  "endDateExclusive": "2026-11-30",
  "runCount": 2,
  "totalDistanceMeters": 4000,
  "totalMovingSeconds": 1620,
  "averagePaceSecondsPerKm": 405
}
```

한국 시간 기준으로 주간은 월요일 00시부터 다음 월요일 00시 전까지, 월간은 매월 1일 00시부터 다음 달 1일 00시 전까지 집계합니다. 기간은 업로드 시점과 관계없이 `finishedAt`으로 결정합니다. 시각이 없는 기록은 목록에서 조회할 수 있으며 기간별 합계에서는 제외됩니다.

평균 페이스는 총 이동 시간(초)을 총 거리(km)로 나눠 반올림합니다. 기록이 없으면 합계는 0이고, 총 거리가 0이면 평균 페이스는 `null`입니다.

## 초기 체력 측정

`GET /assessment`는 인증된 사용자의 초기 측정 진행률을 반환합니다. 초기 측정으로 저장한 주행(`type: "assessment"`) 중 3,000m 이상인 기록만 집계합니다. 동일 러닝의 재전송은 한 번만 집계합니다.

```json
{
  "completedRuns": 2,
  "requiredRuns": 3,
  "status": "in_progress",
  "baselinePaceSecondsPerKm": null
}
```

세 번째 유효 기록이 저장되면 `status`는 `completed`입니다. 기준 페이스는 서버에 저장된 순서로 첫 세 유효 기록의 총 이동 시간(초)을 총 거리(km)로 나눈 뒤 정수로 반올림합니다. 네 번째 이후 주행은 최초 기준 페이스에 영향을 주지 않습니다.

## 활성 목표

`GET /goals/active`는 `200`과 `{"goal":null}` 또는 현재 활성 목표를 반환합니다. 초기 측정 중에도 목표를 설정할 수 있습니다.

`PUT /goals/active`는 JSON 요청으로 사용자당 목표 하나를 생성하거나 교체합니다.

| 목표 | 요청 예시 | 단위 |
| --- | --- | --- |
| 거리 | `{"type":"distance","targetDistanceMeters":5000}` | 미터 |
| 페이스 | `{"type":"pace","targetPaceSecondsPerKm":390}` | 초/km, 값이 작을수록 빠름 |

목표값은 양의 안전한 정수이며 요청에는 `type`과 해당 유형의 목표 필드만 포함합니다. 성공하면 `200`과 `{"goal":{"type":"distance","targetDistanceMeters":5000}}` 형식으로 응답합니다. 잘못된 목표나 JSON에는 `400 invalid_goal`을 반환합니다. 업로드 API와 같은 Bearer 인증을 사용하며, 인증 실패 시 `401 unauthorized`입니다. JSON 요청의 헤더와 최대 크기 조건은 업로드 API와 같습니다.

## 미션 로직 연동

모든 호출에는 로그인 토큰을 사용합니다.

| API | 요청 | 성공 응답 |
| --- | --- | --- |
| `POST /missions/recommend` | `{"date":"2026-11-27"}` | `200`과 `locked`, `needs_goal` 또는 `ready` 상태. `ready`에는 `missions` 목록이 있고 각 미션의 `id`는 서버가 발급합니다. |
| `POST /missions/start` | `{"missionId":"<추천 응답의 id>"}` | `200`과 `{"mission": ...}`. 시작한 미션을 다시 요청해도 같은 조건을 반환합니다. |
| `POST /runs` | `type: "mission"`, 시작 응답의 `mission.id`를 `missionId`로 지정, 기존 러닝 필드 | 처음 저장하면 `201`, 동일 기록 재전송은 `200`. |

앱은 온라인에서 미션을 시작한 뒤 반환된 ID와 조건을 기기에 저장합니다. 달리는 동안 연결이 끊겨도 기록을 유지하고, 같은 계정으로 접속했을 때 같은 `clientRunId`와 `missionId`로 재전송합니다. 서버는 미션에 저장된 조건으로 판정하고 결과·러닝·훈련 상태를 한 트랜잭션에 반영합니다. 목표가 바뀐 뒤 이전 미션을 업로드하면 그 결과는 보존되지만 현재 훈련 상태는 갱신하지 않습니다.

미션 ID가 없거나 다른 계정의 것이면 `404 mission_not_found`, 시작 전이면 `409 mission_not_started`, 완료한 미션에 새 기록을 올리면 `409 mission_already_completed`입니다. 동일 업로드 재전송은 예외로 기존 결과를 반환합니다.

## 테스트

저장소 루트에서 `node --test backend/server.test.mjs`를 실행합니다.

## 이메일 소유 확인 (후속 작업)

현재 가입은 이메일의 형식과 중복 등록 여부를 확인합니다. 실제로 존재하는 주소인지, 가입자가 그 주소의 메일을 받을 수 있는지는 확인하지 않습니다.

추가할 때는 다음 흐름을 사용합니다.

1. 가입 시 해당 주소로 일회용 인증 링크 또는 코드를 보냅니다. 서버는 인증값의 해시와 만료 시각을 계정에 연결해 보관합니다.
2. 사용자가 링크를 열거나 코드를 입력하면 서버가 유효 기간과 사용 여부를 확인하고, 성공 시 인증값을 폐기하면서 계정을 인증 완료로 표시합니다.
3. 앱은 인증 대기 상태와 재발송 안내를 표시합니다. 서버는 재발송과 확인 시도에 횟수 제한을 적용합니다.

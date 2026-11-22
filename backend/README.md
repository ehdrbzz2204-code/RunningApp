# RunningApp 백엔드

Node.js 24에서 실행합니다. SQLite 데이터는 기본적으로 `backend/data/runs.db`에 저장됩니다.

```powershell
$runToken = node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('hex'))"
$env:RUN_API_TOKENS = @{ $runToken = 'local-runner' } | ConvertTo-Json -Compress
node backend/server.mjs
```

서버는 로컬 주소 `127.0.0.1:3000`에서 시작합니다. 개발용 Bearer 토큰과 사용자 ID 매핑을 환경 변수로 설정합니다. 실제 로그인과 HTTPS는 모바일 배포 전에 연동합니다.

## 러닝 업로드

`POST /runs`에 `Authorization: Bearer <token>`과 `Content-Type: application/json`을 보냅니다.

| 요청 필드 | 타입 | 제약 |
| --- | --- | --- |
| `clientRunId` | 문자열 | 필수. 러닝마다 앱에서 발급한 고유 ID. 영문자, 숫자, `_`, `-`로 구성된 1~128자. UUID 권장. |
| `distanceMeters` | 정수 | 필수. 미터 단위, 0 이상. |
| `movingSeconds` | 정수 | 필수. 초 단위, 1 이상. |
| `type` | 문자열 | 필수. `free` 또는 `assessment`. |
| `missionId` | null | 현재 생략하거나 `null`을 전송. |

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

미션 주행은 미션의 사용자 소유권을 서버에서 검증할 수 있을 때 연결합니다.

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

미션 추천에 사용할 입력은 초기 측정의 `status`, `baselinePaceSecondsPerKm`와 현재 활성 목표입니다. 추천 가능 조건은 측정 완료와 활성 목표 설정입니다. 향후 서버 내부에서도 `summarizeAssessment`와 저장소의 `getActiveGoal`을 사용해 같은 입력을 얻을 수 있습니다.

## 테스트

저장소 루트에서 `node --test backend/server.test.mjs`를 실행합니다.

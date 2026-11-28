# 미션 로직

초기 체력 측정 결과, 활성 목표, 이전 수행 결과를 바탕으로 미션을 추천하고 판정하는 규칙과 테스트를 둡니다. 기능 작업은 `feature/mission`에서 진행하고 `develop`으로 PR을 엽니다.

| 파일 | 내용 |
| --- | --- |
| `mission-logic.mjs` | 추천·판정·난이도 조정 규칙. 저장소와 네트워크에 의존하지 않는 순수 함수만 있습니다. 백엔드가 import해서 씁니다. |
| `mission-logic.test.mjs` | 테스트. 입력 형식이 어긋나지 않는지 보려고 백엔드 `runner-profile.mjs`를 직접 불러옵니다. |
| `android/MissionProgress.kt` | 러닝 측정 화면의 진행률 계산. `trackMission`과 규칙이 같습니다. 앱 모듈에 복사해서 씁니다. |

단위와 필드 이름은 백엔드 API와 같습니다. 거리는 `…Meters`, 시간은 `movingSeconds`, 페이스는 `…PaceSecondsPerKm`(초/km)입니다.

## 테스트

저장소 루트에서 `node --test mission/mission-logic.test.mjs`를 실행합니다.

## 함수

| 함수 | 입력 | 출력 | 요구사항 |
| --- | --- | --- | --- |
| `recommendMissions({ assessment, goal, trainingState, date })` | `summarizeAssessment()` 결과, `getActiveGoal()` 결과, 저장된 훈련 상태(처음이면 `null`), 사용자 날짜 `"YYYY-MM-DD"` | `locked` / `needs_goal` / `ready` 응답 | FR-04, UC-02 2a |
| `evaluateMission(mission, run)` | 미션, `{ distanceMeters, movingSeconds }` | 성공·미달성과 실제 기록 | UC-02 5, 4a-1·2 |
| `applyMissionResult({ trainingState, goal, mission, result })` | 판정 결과 | 새 `trainingState`, 결과 화면 문구, `goalAchieved` | UC-02 4a-3·4 |
| `validateGoal(goal, assessment)` | `normalizeGoal()`을 통과한 목표 | `{ valid, errors[], warnings[] }`. 각 항목은 `{ code, message }` | FR-03 |
| `adjustStateForGoal(trainingState, goal)` | 목표를 바꿨을 때 | 새 목표에 맞춘 훈련 상태 | FR-03 |
| `trackMission(mission, { distanceMeters, movingSeconds, gpsAvailable })` | 러닝 중 측정값 | 진행률, 남은 거리, 페이스 상태, 남은 구간 필요 페이스 | S-02 |
| `fitnessLevel(baselinePaceSecondsPerKm)` | 기준 페이스 | `{ level: 1~5, label }` | FR-01 |

같은 입력이면 항상 같은 미션이 나옵니다. 그래서 같은 날 다시 요청해도 미션이 바뀌지 않고, 앱이 캐시한 미션과 서버 미션을 id로 대조할 수 있습니다.

## 백엔드 연동 제안

### 1. 훈련 상태 저장

`active_goals`와 같은 형식으로 사용자당 한 행을 둡니다. 값은 `recommendMissions`·`applyMissionResult`가 돌려준 `trainingState`를 JSON 그대로 저장하면 됩니다.

```sql
CREATE TABLE IF NOT EXISTS training_states (
  user_id TEXT PRIMARY KEY NOT NULL,
  payload TEXT NOT NULL
) STRICT;
```

### 2. `GET /missions/today?date=YYYY-MM-DD`

```js
import { recommendMissions } from "../mission/mission-logic.mjs";

const assessment = summarizeAssessment(storage.getAssessmentRuns(userId, assessmentRequirements));
const result = recommendMissions({
  assessment,
  goal: storage.getActiveGoal(userId),
  trainingState: storage.getTrainingState(userId), // 없으면 null
  date,                                            // 앱이 기기 날짜로 보냄
});
if (result.status === "ready") storage.putTrainingState(userId, result.trainingState);
const { trainingState, ...body } = result;         // 훈련 상태는 응답에서 빼도 됩니다
reply(response, 200, body);
```

응답 예시:

```json
{
  "status": "ready",
  "date": "2026-11-26",
  "fitness": { "level": 2, "label": "초급", "baselinePaceSecondsPerKm": 432 },
  "missions": [
    {
      "id": "2026-11-26-0-easy", "date": "2026-11-26", "type": "easy",
      "title": "편안하게 2.5km 달리기",
      "description": "옆 사람과 대화할 수 있을 정도의 속도로 꾸준히 달려요.",
      "targetDistanceMeters": 2500,
      "maxPaceSecondsPerKm": 537, "minPaceSecondsPerKm": 447, "recommendedPaceSecondsPerKm": 492
    }
  ]
}
```

| `status` | 추가 필드 | 앱 화면 |
| --- | --- | --- |
| `locked` | `completedRuns`, `requiredRuns`, `remainingRuns`, `message` | 미션 잠김 (오류나 빈 목록이 아님) |
| `needs_goal` | `fitness`, `message` | 목표 설정으로 유도 |
| `ready` | `date`, `fitness`, `missions[]` | 오늘의 미션. 앱의 `MissionRecommendation(title, description)`에 그대로 매핑 |

### 3. 미션 주행 판정

백엔드는 추천할 때 사용자별 미션의 조건을 저장하고 ID를 발급합니다. `POST /runs`로 미션 주행이 들어오면 해당 사용자가 시작한 미션의 저장된 조건으로 `evaluateMission`과 `applyMissionResult`를 호출합니다. 러닝·판정 결과·훈련 상태는 한 트랜잭션에서 처리하며, 동일한 `clientRunId`를 재전송하면 저장된 결과를 반환합니다.

요청과 응답 형식은 [백엔드 API 명세](../backend/README.md#미션-로직-연동)를 참고하세요.

### 4. 목표 저장

`PUT /goals/active`에서 `normalizeGoal` 다음에 `validateGoal(goal, assessment)`를 호출합니다. `errors`가 있으면 `400`을 반환하고, `warnings`는 응답에 넣어 앱이 안내하게 합니다. 목표를 바꾼 뒤에는 저장된 훈련 상태가 있으면 `adjustStateForGoal`로 갱신합니다.

## 규칙 요약

- **체력 레벨**: 기준 페이스로 정합니다. 5:15 이하는 5, 6:00 이하는 4, 7:00 이하는 3, 8:00 이하는 2, 그보다 느리면 1입니다. 레벨별 시작 편한 거리는 2 / 2.5 / 3 / 4 / 5km입니다.
- **시작 페이스**: 편한 페이스는 기준 페이스 +60초, 템포 페이스는 −5초입니다.
- **오늘의 미션**: 편안한 러닝 1개에, 날짜에 따라 거리 늘리기 또는 템포 러닝(페이스 목표면 목표 페이스 체험)을 더합니다. 실력이 목표에 닿으면 목표 도전 미션을 냅니다.
- **목표에 맞춘 상한**: 거리 목표면 목표 거리를 넘는 미션을 만들지 않습니다. 페이스 목표면 목표보다 빠르게 훈련시키지 않습니다. 페이스 목표의 기준 거리는 5km입니다(`paceGoalReferenceMeters`).
- **판정**: 목표 거리의 98% 이상을 달리고 평균 페이스가 `maxPace` 이하면 성공입니다. `minPace`보다 빠르면 실패가 아니라 안내만 합니다.
- **성공했을 때**:
  - 거리 늘리기는 +10%(최소 250m), 템포는 −3초 빨라집니다.
  - 편안한 러닝은 2회 연속 성공마다 +250m 늘어납니다.
- **미달성했을 때**:
  - 목표 거리의 90% 이상 달렸으면 같은 거리로 재도전합니다.
  - 그보다 짧으면 다음 거리를 줄이되 최대 20%까지만 줄입니다.
  - 페이스가 느렸으면 느린 만큼의 절반을 완화합니다.
  - 2회 연속 미달성이면 다음에는 회복 러닝 1개만 줍니다.

수치는 모두 `missionRules`와 파일 상단 표에 모여 있어서, 실제로 달려 본 뒤 조정할 수 있습니다.

import assert from "node:assert/strict";
import { test } from "node:test";
import { normalizeGoal, summarizeAssessment } from "../backend/runner-profile.mjs";
import {
  applyMissionResult, createTrainingState, evaluateMission, fitnessLevel, formatKm, formatPace,
  missionTypes, recommendMissions, trackMission, validateGoal,
} from "./mission-logic.mjs";

// 백엔드와 같은 방식으로 초기 측정 결과를 만듭니다.
const assessed = (pace) => summarizeAssessment([3000, 3000, 3000].map((d) => ({ distanceMeters: d, movingSeconds: pace * 3 })));
const run = (distanceMeters, pace) => ({ distanceMeters: Math.round(distanceMeters), movingSeconds: Math.round(distanceMeters / 1000 * pace) });
const EVEN_DAY = "2026-11-26"; // 날짜별 2번째 미션 순환 확인용
const ODD_DAY = "2026-11-27";

test("백엔드 summarizeAssessment·normalizeGoal 결과를 그대로 입력으로 쓴다", () => {
  const goal = normalizeGoal({ type: "distance", targetDistanceMeters: 5000 });
  const result = recommendMissions({ assessment: assessed(432), goal, date: EVEN_DAY });
  assert.equal(result.status, "ready");
  assert.equal(result.fitness.level, 2);
  assert.ok(result.trainingState, "처음이면 저장할 훈련 상태를 함께 반환");
});

test("FR-01/UC-02 2a: 초기 측정 3회 미만이면 잠김과 남은 횟수", () => {
  const two = summarizeAssessment([{ distanceMeters: 3000, movingSeconds: 1260 }, { distanceMeters: 3100, movingSeconds: 1300 }]);
  const r = recommendMissions({ assessment: two, goal: { type: "distance", targetDistanceMeters: 5000 }, date: EVEN_DAY });
  assert.deepEqual([r.status, r.completedRuns, r.remainingRuns], ["locked", 2, 1]);
});

test("측정 완료 + 목표 없음이면 needs_goal", () => {
  assert.equal(recommendMissions({ assessment: assessed(432), goal: null, date: EVEN_DAY }).status, "needs_goal");
});

test("체력 레벨 경계", () => {
  assert.deepEqual([315, 316, 420, 480, 481].map((p) => fitnessLevel(p).level), [5, 4, 3, 2, 1]);
});

test("FR-03: 목표 범위와 현실성 확인", () => {
  assert.ok(validateGoal({ type: "distance", targetDistanceMeters: 5000 }).valid);
  assert.equal(validateGoal({ type: "distance", targetDistanceMeters: 500 }).errors[0].code, "distance_too_short");
  assert.equal(validateGoal({ type: "pace", targetPaceSecondsPerKm: 150 }).errors[0].code, "pace_too_fast");
  const a = assessed(432);
  assert.equal(validateGoal({ type: "pace", targetPaceSecondsPerKm: 450 }, a).warnings[0].code, "pace_already_reached");
  assert.equal(validateGoal({ type: "pace", targetPaceSecondsPerKm: 300 }, a).warnings[0].code, "pace_ambitious");
  assert.equal(validateGoal({ type: "pace", targetPaceSecondsPerKm: 400 }, a).warnings.length, 0);
  // 측정 중에도 목표 설정 가능
  const inProgress = summarizeAssessment([]);
  assert.ok(validateGoal({ type: "pace", targetPaceSecondsPerKm: 400 }, inProgress).valid);
});

test("FR-04: 거리와 페이스가 명시되고 체력에 따라 다르다", () => {
  const goal = { type: "distance", targetDistanceMeters: 10000 };
  const beginner = recommendMissions({ assessment: assessed(560), goal, date: EVEN_DAY });
  const mid = recommendMissions({ assessment: assessed(345), goal, date: EVEN_DAY });
  for (const m of [...beginner.missions, ...mid.missions]) {
    assert.ok(m.targetDistanceMeters >= 1000 && m.maxPaceSecondsPerKm > 0 && m.recommendedPaceSecondsPerKm > 0);
    assert.ok(m.title && m.description);
  }
  assert.equal(beginner.missions[0].title, "편안하게 2km 달리기");
  assert.ok(beginner.missions[0].targetDistanceMeters < mid.missions[0].targetDistanceMeters);
  assert.ok(beginner.missions[0].recommendedPaceSecondsPerKm > mid.missions[0].recommendedPaceSecondsPerKm);
});

test("FR-04: 두 번째 미션은 날짜별로 거리 늘리기/템포가 번갈아 나온다", () => {
  const goal = { type: "distance", targetDistanceMeters: 10000 };
  const types = [EVEN_DAY, ODD_DAY].map((date) => recommendMissions({ assessment: assessed(432), goal, date }).missions[1].type);
  assert.deepEqual(new Set(types), new Set([missionTypes.long, missionTypes.tempo]));
});

test("같은 입력이면 같은 미션 (재요청·캐시 대조 시 일치)", () => {
  const goal = { type: "pace", targetPaceSecondsPerKm: 400 };
  const a = recommendMissions({ assessment: assessed(432), goal, date: EVEN_DAY });
  const b = recommendMissions({ assessment: assessed(432), goal, trainingState: a.trainingState, date: EVEN_DAY });
  assert.deepEqual(a.missions, b.missions);
});

test("페이스 목표면 목표보다 빠르게 훈련시키지 않는다", () => {
  const s = createTrainingState(assessed(300), { type: "pace", targetPaceSecondsPerKm: 330 });
  assert.ok(s.tempoPaceSecondsPerKm >= 330);
});

test("판정: GPS 오차 2퍼센트 허용, 실제 값 저장", () => {
  const goal = { type: "distance", targetDistanceMeters: 5000 };
  const m = recommendMissions({ assessment: assessed(432), goal, date: EVEN_DAY }).missions[0];
  const ok = evaluateMission(m, run(m.targetDistanceMeters * 0.985, m.recommendedPaceSecondsPerKm));
  assert.equal(ok.outcome, "success");
  const short = evaluateMission(m, run(1200, 480));
  assert.deepEqual([short.outcome, short.failReason, short.distanceMeters], ["failed", "distance_short", 1200]);
});

test("UC-02 4a: 미달성이면 다음 거리를 낮추되 최대 20퍼센트까지만", () => {
  const goal = { type: "distance", targetDistanceMeters: 10000 };
  const rec = recommendMissions({ assessment: assessed(432), goal, date: EVEN_DAY });
  const m = rec.missions[0]; // 편안하게 2.5km
  const result = evaluateMission(m, run(1200, 480));
  const next = applyMissionResult({ trainingState: rec.trainingState, goal, mission: m, result });
  assert.equal(next.trainingState.easyDistanceMeters, 2000);
  assert.match(next.feedback.nextHint, /2km/);
  assert.equal(next.feedback.title, "아쉽게 미달성");
});

test("90퍼센트 이상 달렸으면 같은 거리로 재도전", () => {
  const goal = { type: "distance", targetDistanceMeters: 10000 };
  const rec = recommendMissions({ assessment: assessed(432), goal, date: EVEN_DAY });
  const m = rec.missions[0];
  const result = evaluateMission(m, run(m.targetDistanceMeters * 0.92, 480));
  const next = applyMissionResult({ trainingState: rec.trainingState, goal, mission: m, result });
  assert.equal(next.trainingState.easyDistanceMeters, rec.trainingState.easyDistanceMeters);
});

test("페이스가 느려 미달성이면 페이스를 완화", () => {
  const goal = { type: "pace", targetPaceSecondsPerKm: 400 };
  const rec = recommendMissions({ assessment: assessed(432), goal, date: EVEN_DAY });
  const tempo = [EVEN_DAY, ODD_DAY].flatMap((date) => recommendMissions({ assessment: assessed(432), goal, date }).missions)
    .find((m) => m.type === missionTypes.tempo);
  const result = evaluateMission(tempo, run(tempo.targetDistanceMeters, tempo.maxPaceSecondsPerKm + 20));
  assert.equal(result.failReason, "pace_too_slow");
  const next = applyMissionResult({ trainingState: rec.trainingState, goal, mission: tempo, result });
  assert.equal(next.trainingState.tempoPaceSecondsPerKm, rec.trainingState.tempoPaceSecondsPerKm + 10);
});

test("2회 연속 미달성이면 다음은 회복 러닝 1개", () => {
  const goal = { type: "distance", targetDistanceMeters: 10000 };
  let state = null;
  for (const date of [EVEN_DAY, ODD_DAY]) {
    const rec = recommendMissions({ assessment: assessed(432), goal, trainingState: state, date });
    const m = rec.missions[0];
    state = applyMissionResult({ trainingState: rec.trainingState, goal, mission: m, result: evaluateMission(m, run(500, 500)) }).trainingState;
  }
  const rec = recommendMissions({ assessment: assessed(432), goal, trainingState: state, date: "2026-11-28" });
  assert.equal(rec.missions.length, 1);
  assert.match(rec.missions[0].description, /회복/);
});

for (const goal of [{ type: "distance", targetDistanceMeters: 5000 }, { type: "pace", targetPaceSecondsPerKm: 400 }]) {
  test(`꾸준히 성공하면 목표 도전에 도달하고 성공 시 목표 달성 (${goal.type})`, () => {
    let state = null;
    let achieved = false;
    const start = Date.parse("2026-11-01T00:00:00Z");
    for (let i = 0; i < 60 && !achieved; i++) {
      const date = new Date(start + i * 86_400_000).toISOString().slice(0, 10);
      const rec = recommendMissions({ assessment: assessed(432), goal, trainingState: state, date });
      state = rec.trainingState;
      for (const m of rec.missions) {
        const result = evaluateMission(m, run(m.targetDistanceMeters, m.recommendedPaceSecondsPerKm));
        assert.equal(result.outcome, "success", `${m.title}은 권장 페이스로 완주하면 성공해야 함`);
        const next = applyMissionResult({ trainingState: state, goal, mission: m, result });
        state = next.trainingState;
        if (next.goalAchieved) achieved = true;
      }
    }
    assert.ok(achieved);
    if (goal.type === "distance") assert.ok(state.longDistanceMeters <= 5000);
    else assert.ok(state.tempoPaceSecondsPerKm >= 400);
  });
}

test("편안한 러닝은 2회 연속 성공마다 250m 늘어난다 (하루 2개 미션이어도)", () => {
  const goal = { type: "distance", targetDistanceMeters: 10000 };
  let state = null;
  for (const date of [EVEN_DAY, ODD_DAY]) {
    const rec = recommendMissions({ assessment: assessed(432), goal, trainingState: state, date });
    state = rec.trainingState;
    for (const m of rec.missions) {
      const result = evaluateMission(m, run(m.targetDistanceMeters, m.recommendedPaceSecondsPerKm));
      state = applyMissionResult({ trainingState: state, goal, mission: m, result }).trainingState;
    }
  }
  assert.equal(state.easyDistanceMeters, 2750);
});

test("실시간 진행률·남은 거리·필요 페이스·GPS 끊김", () => {
  const easy = { type: "easy", targetDistanceMeters: 3000, maxPaceSecondsPerKm: 480, minPaceSecondsPerKm: 360 };
  const live = trackMission(easy, { distanceMeters: 2100, movingSeconds: 907 });
  assert.equal(live.remainingMeters, 900);
  assert.equal(live.paceStatus, "on_target");
  assert.equal(live.requiredPaceForRemaining, null);

  const tempo = { type: "tempo", targetDistanceMeters: 2000, maxPaceSecondsPerKm: 400, minPaceSecondsPerKm: 370 };
  const slow = trackMission(tempo, { distanceMeters: 1000, movingSeconds: 420 });
  assert.deepEqual([slow.paceStatus, slow.requiredPaceForRemaining], ["too_slow", 380]);
  assert.equal(trackMission(tempo, { distanceMeters: 1000, movingSeconds: 430, gpsAvailable: false }).paceStatus, "gps_lost");
  assert.equal(trackMission(tempo, { distanceMeters: 150, movingSeconds: 60 }).paceStatus, "measuring");
});

test("표시 형식", () => {
  assert.equal(formatPace(432), "7′12″");
  assert.equal(formatPace(null), "—");
  assert.deepEqual([2000, 2500, 2250].map(formatKm), ["2km", "2.5km", "2.25km"]);
});

test("결과는 JSON으로 그대로 저장·응답할 수 있다", () => {
  const goal = { type: "distance", targetDistanceMeters: 5000 };
  const rec = recommendMissions({ assessment: assessed(432), goal, date: EVEN_DAY });
  assert.deepEqual(JSON.parse(JSON.stringify(rec)), rec);
});

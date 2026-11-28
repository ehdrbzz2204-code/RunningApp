// 미션 로직: 초기 체력 측정 결과, 활성 목표, 이전 수행 결과로 미션을 추천하고 판정합니다.
// 저장소·네트워크에 의존하지 않는 순수 함수만 둡니다. 저장은 백엔드가 담당합니다.
// 단위는 백엔드 API와 같습니다: 거리 m, 시간 초, 페이스 초/km(값이 작을수록 빠름).

export const missionRules = Object.freeze({
  distanceStepMeters: 250,
  minimumMissionDistanceMeters: 1000,
  // GPS 오차를 고려해 목표 거리의 98% 이상이면 거리 달성으로 봅니다.
  distanceTolerance: 0.98,
  // 미달성이라도 목표 거리의 90% 이상이면 같은 거리로 재도전합니다.
  retryThreshold: 0.9,
  // 거리 미달성 시 다음 거리는 최대 20%까지만 줄입니다.
  maxDecreaseRatio: 0.8,
  // 페이스 목표에는 거리 필드가 없으므로 기준 거리를 5km로 둡니다.
  paceGoalReferenceMeters: 5000,
  // 실시간 판정에서 처음 200m는 GPS 오차로 페이스를 판단하지 않습니다.
  paceWarmupMeters: 200,
});

export const missionTypes = Object.freeze({
  easy: "easy",
  long: "long",
  tempo: "tempo",
  goalPace: "goal_pace",
  goalChallenge: "goal_challenge",
});

const { distanceStepMeters: STEP, minimumMissionDistanceMeters: MIN_DISTANCE } = missionRules;

/* ------------------------------------------------------------------ */
/* 표시 도우미                                                          */
/* ------------------------------------------------------------------ */

/** 432 → "7′12″" */
export function formatPace(secondsPerKm) {
  if (!Number.isFinite(secondsPerKm) || secondsPerKm <= 0) return "—";
  const s = Math.round(secondsPerKm);
  return `${Math.floor(s / 60)}′${String(s % 60).padStart(2, "0")}″`;
}

/** 2000 → "2km", 2500 → "2.5km", 2250 → "2.25km" */
export function formatKm(meters) {
  return `${Number((meters / 1000).toFixed(2))}km`;
}

const floorTo = (meters, step = STEP) => Math.floor(meters / step) * step;
const roundTo = (meters, step = STEP) => Math.round(meters / step) * step;
const clamp = (value, min, max) => Math.min(Math.max(value, min), Math.max(min, max));

export function paceOf(distanceMeters, movingSeconds) {
  if (!(distanceMeters > 0) || !(movingSeconds > 0)) return null;
  return Math.round(movingSeconds / (distanceMeters / 1000));
}

/* ------------------------------------------------------------------ */
/* 체력 수준                                                            */
/* ------------------------------------------------------------------ */

const levels = [
  { level: 5, label: "중상급", slowestPaceSecondsPerKm: 315 },
  { level: 4, label: "중급", slowestPaceSecondsPerKm: 360 },
  { level: 3, label: "초중급", slowestPaceSecondsPerKm: 420 },
  { level: 2, label: "초급", slowestPaceSecondsPerKm: 480 },
  { level: 1, label: "입문", slowestPaceSecondsPerKm: Infinity },
];

/** 기준 페이스(summarizeAssessment의 baselinePaceSecondsPerKm)로 1~5 레벨을 산출합니다. */
export function fitnessLevel(baselinePaceSecondsPerKm) {
  const { level, label } = levels.find((l) => baselinePaceSecondsPerKm <= l.slowestPaceSecondsPerKm);
  return { level, label, baselinePaceSecondsPerKm };
}

/* ------------------------------------------------------------------ */
/* 목표 검증 (PUT /goals/active 에서 normalizeGoal 다음에 호출)            */
/* ------------------------------------------------------------------ */

/**
 * normalizeGoal이 통과시킨 목표의 범위와 현실성을 확인합니다.
 * errors가 있으면 저장하지 않고, warnings는 저장하되 앱에 안내합니다.
 * 초기 측정 중에도 목표를 설정할 수 있으므로 assessment는 in_progress여도 됩니다.
 */
export function validateGoal(goal, assessment = null) {
  const errors = [];
  const warnings = [];
  const baseline = assessment?.status === "completed" ? assessment.baselinePaceSecondsPerKm : null;

  if (goal?.type === "distance") {
    const t = goal.targetDistanceMeters;
    if (t < 1000) errors.push({ code: "distance_too_short", message: "목표 거리는 1km 이상이어야 해요." });
    if (t > 42195) errors.push({ code: "distance_too_long", message: "목표 거리는 42.195km 이하로 설정해 주세요." });
    if (!errors.length && baseline !== null && fitnessLevel(baseline).level <= 2 && t > 12000) {
      warnings.push({ code: "distance_ambitious", message: "현재 수준에서는 긴 목표예요. 미션이 단계적으로 거리를 늘려 갈게요." });
    }
  } else if (goal?.type === "pace") {
    const p = goal.targetPaceSecondsPerKm;
    if (p < 180) errors.push({ code: "pace_too_fast", message: "목표 페이스는 3′00″보다 느리게 설정해 주세요." });
    if (p > 720) errors.push({ code: "pace_too_slow", message: "목표 페이스는 12′00″보다 빠르게 설정해 주세요." });
    if (!errors.length && baseline !== null) {
      if (p >= baseline) {
        warnings.push({ code: "pace_already_reached", message: `이미 측정 기록(${formatPace(baseline)}/km)보다 느린 목표예요. 더 빠른 목표를 권장해요.` });
      } else if ((baseline - p) / baseline > 0.2) {
        warnings.push({ code: "pace_ambitious", message: "측정 기록보다 20% 이상 빠른 목표예요. 장기 목표로 잡고 단계적으로 접근할게요." });
      }
    }
  } else {
    errors.push({ code: "invalid_goal", message: "목표 유형을 확인해 주세요." });
  }
  return { valid: errors.length === 0, errors, warnings };
}

/* ------------------------------------------------------------------ */
/* 훈련 상태: 다음 미션 난이도를 결정하는 사용자별 값                          */
/* ------------------------------------------------------------------ */

const easyDistanceByLevel = { 1: 2000, 2: 2500, 3: 3000, 4: 4000, 5: 5000 };
const tempoDistanceByLevel = { 1: 1500, 2: 2000, 3: 2000, 4: 3000, 5: 3000 };

/** 초기 측정 완료 후 첫 추천 때 만들고, 이후 미션 결과마다 갱신해 저장합니다. */
export function createTrainingState(assessment, goal) {
  if (assessment?.status !== "completed") throw new Error("assessment_not_completed");
  const base = assessment.baselinePaceSecondsPerKm;
  const { level } = fitnessLevel(base);
  const easy = easyDistanceByLevel[level];
  return adjustStateForGoal({
    easyDistanceMeters: easy,
    longDistanceMeters: roundTo(easy * 1.5),
    tempoDistanceMeters: tempoDistanceByLevel[level],
    easyPaceSecondsPerKm: base + 60, // 대화가 가능한 편한 속도
    tempoPaceSecondsPerKm: base - 5, // 측정 페이스보다 약간 빠르게
    consecutiveSuccess: 0,
    consecutiveFail: 0,
    easySuccessStreak: 0, // 편안한 러닝 연속 성공 수 (2회마다 거리 +250m)
    completedMissionCount: 0,
    recoveryNext: false,
  }, goal);
}

function capsFor(goal) {
  if (goal.type === "distance") {
    const t = goal.targetDistanceMeters;
    return {
      easyMax: Math.max(2000, floorTo(t * 0.7)),
      longMax: Math.max(MIN_DISTANCE, t),
      tempoMax: Math.max(MIN_DISTANCE, floorTo(t * 0.5)),
      fastestTempoPace: 180,
    };
  }
  const ref = missionRules.paceGoalReferenceMeters;
  return {
    easyMax: Math.max(2000, ref),
    longMax: floorTo(ref * 1.5),
    tempoMax: ref,
    fastestTempoPace: goal.targetPaceSecondsPerKm, // 목표보다 빠르게 훈련시키지 않습니다.
  };
}

/** 목표를 바꿨을 때도 호출해 거리 상한과 페이스 하한을 다시 맞춥니다. */
export function adjustStateForGoal(state, goal) {
  const c = capsFor(goal);
  const tempoPace = Math.max(state.tempoPaceSecondsPerKm, c.fastestTempoPace);
  return {
    ...state,
    easyDistanceMeters: clamp(state.easyDistanceMeters, MIN_DISTANCE, c.easyMax),
    longDistanceMeters: clamp(state.longDistanceMeters, MIN_DISTANCE, c.longMax),
    tempoDistanceMeters: clamp(state.tempoDistanceMeters, MIN_DISTANCE, c.tempoMax),
    tempoPaceSecondsPerKm: tempoPace,
    easyPaceSecondsPerKm: Math.max(state.easyPaceSecondsPerKm, tempoPace + 45),
  };
}

/* ------------------------------------------------------------------ */
/* 미션 추천                                                            */
/* ------------------------------------------------------------------ */

function readyForChallenge(goal, s) {
  if (s.consecutiveSuccess < 1) return false;
  if (goal.type === "distance") return s.longDistanceMeters >= goal.targetDistanceMeters;
  return s.tempoPaceSecondsPerKm <= goal.targetPaceSecondsPerKm + 5 &&
    s.tempoDistanceMeters >= missionRules.paceGoalReferenceMeters * 0.6;
}

function dayNumber(date) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date ?? "")) throw new Error("invalid_date");
  const ms = Date.parse(`${date}T00:00:00Z`);
  if (Number.isNaN(ms)) throw new Error("invalid_date");
  return Math.floor(ms / 86_400_000);
}

/**
 * 오늘의 미션을 추천합니다. 같은 입력이면 항상 같은 결과를 반환합니다.
 *
 * @param assessment summarizeAssessment() 결과
 * @param goal getActiveGoal() 결과 또는 null
 * @param trainingState 저장된 훈련 상태 또는 null(처음이면 새로 만들어 반환)
 * @param date 사용자 기준 날짜 "YYYY-MM-DD" (앱이 기기 날짜로 보냅니다)
 */
export function recommendMissions({ assessment, goal, trainingState = null, date }) {
  if (assessment?.status !== "completed") {
    const completedRuns = assessment?.completedRuns ?? 0;
    const requiredRuns = assessment?.requiredRuns ?? 3;
    const remainingRuns = requiredRuns - completedRuns;
    return {
      status: "locked",
      completedRuns,
      requiredRuns,
      remainingRuns,
      message: `3km 자유 주행을 ${remainingRuns}번 더 완료하면 맞춤 미션이 열려요.`,
    };
  }
  const fitness = fitnessLevel(assessment.baselinePaceSecondsPerKm);
  if (!goal) {
    return { status: "needs_goal", fitness, message: "러닝 목표를 설정하면 맞춤 미션을 받을 수 있어요." };
  }

  const state = trainingState ? adjustStateForGoal(trainingState, goal) : createTrainingState(assessment, goal);
  const day = dayNumber(date);
  let types;
  if (state.recoveryNext) {
    types = [missionTypes.easy];
  } else if (goal.type === "distance") {
    const second = readyForChallenge(goal, state) ? missionTypes.goalChallenge
      : day % 2 === 0 ? missionTypes.long : missionTypes.tempo;
    types = [missionTypes.easy, second];
  } else {
    const nearGoal = state.tempoPaceSecondsPerKm - goal.targetPaceSecondsPerKm <= 30;
    const second = readyForChallenge(goal, state) ? missionTypes.goalChallenge
      : day % 2 === 0 ? missionTypes.tempo
      : nearGoal ? missionTypes.goalPace : missionTypes.long;
    types = [missionTypes.easy, second];
  }

  return {
    status: "ready",
    date,
    fitness,
    missions: types.map((type, index) => buildMission(type, index, date, goal, state)),
    trainingState: state,
  };
}

function buildMission(type, index, date, goal, s) {
  const base = { id: `${date}-${index}-${type}`, date, type };
  switch (type) {
    case missionTypes.easy: {
      const d = s.recoveryNext ? Math.max(MIN_DISTANCE, floorTo(s.easyDistanceMeters * 0.8)) : s.easyDistanceMeters;
      return {
        ...base,
        title: `편안하게 ${formatKm(d)} 달리기`,
        description: s.recoveryNext
          ? "최근 미션이 힘들었어요. 오늘은 가볍게 회복 러닝을 해요."
          : "옆 사람과 대화할 수 있을 정도의 속도로 꾸준히 달려요.",
        targetDistanceMeters: d,
        maxPaceSecondsPerKm: s.easyPaceSecondsPerKm + 45,
        minPaceSecondsPerKm: s.easyPaceSecondsPerKm - 45,
        recommendedPaceSecondsPerKm: s.easyPaceSecondsPerKm,
      };
    }
    case missionTypes.long:
      return {
        ...base,
        title: `${formatKm(s.longDistanceMeters)} 끝까지 달리기`,
        description: "속도보다 완주가 목표예요. 힘들면 천천히 달려도 괜찮아요.",
        targetDistanceMeters: s.longDistanceMeters,
        maxPaceSecondsPerKm: s.easyPaceSecondsPerKm + 60,
        minPaceSecondsPerKm: s.easyPaceSecondsPerKm - 45,
        recommendedPaceSecondsPerKm: s.easyPaceSecondsPerKm + 10,
      };
    case missionTypes.tempo:
      return {
        ...base,
        title: `${formatKm(s.tempoDistanceMeters)}를 ${formatPace(s.tempoPaceSecondsPerKm)} 페이스로`,
        description: "조금 숨이 차지만 유지할 수 있는 속도로 일정하게 달려요.",
        targetDistanceMeters: s.tempoDistanceMeters,
        maxPaceSecondsPerKm: s.tempoPaceSecondsPerKm + 5,
        minPaceSecondsPerKm: s.tempoPaceSecondsPerKm - 30,
        recommendedPaceSecondsPerKm: s.tempoPaceSecondsPerKm,
      };
    case missionTypes.goalPace: {
      const d = Math.max(MIN_DISTANCE, floorTo(s.tempoDistanceMeters * 0.5));
      const p = goal.targetPaceSecondsPerKm;
      return {
        ...base,
        title: `목표 페이스 ${formatPace(p)}로 ${formatKm(d)}`,
        description: "목표 페이스를 몸에 익혀요. 짧은 거리지만 페이스를 지키는 것이 핵심이에요.",
        targetDistanceMeters: d,
        maxPaceSecondsPerKm: p + 5,
        minPaceSecondsPerKm: p - 20,
        recommendedPaceSecondsPerKm: p,
      };
    }
    case missionTypes.goalChallenge:
      if (goal.type === "distance") {
        return {
          ...base,
          title: `목표 도전! ${formatKm(goal.targetDistanceMeters)} 완주`,
          description: "지금까지의 훈련으로 목표에 도전할 준비가 됐어요. 페이스는 편하게 유지하세요.",
          targetDistanceMeters: goal.targetDistanceMeters,
          maxPaceSecondsPerKm: s.easyPaceSecondsPerKm + 60,
          minPaceSecondsPerKm: null,
          recommendedPaceSecondsPerKm: s.easyPaceSecondsPerKm + 10,
        };
      }
      return {
        ...base,
        title: `목표 도전! ${formatKm(missionRules.paceGoalReferenceMeters)}를 ${formatPace(goal.targetPaceSecondsPerKm)}로`,
        description: "목표 페이스로 기준 거리를 완주하면 목표 달성이에요.",
        targetDistanceMeters: missionRules.paceGoalReferenceMeters,
        maxPaceSecondsPerKm: goal.targetPaceSecondsPerKm + 3,
        minPaceSecondsPerKm: null,
        recommendedPaceSecondsPerKm: goal.targetPaceSecondsPerKm,
      };
    default:
      throw new Error(`unknown_mission_type:${type}`);
  }
}

/* ------------------------------------------------------------------ */
/* 판정                                                                */
/* ------------------------------------------------------------------ */

/**
 * 러닝 종료 후 미션 성공 여부를 판정합니다.
 * run은 POST /runs와 같은 필드({ distanceMeters, movingSeconds })를 씁니다.
 */
export function evaluateMission(mission, run) {
  const paceSecondsPerKm = paceOf(run.distanceMeters, run.movingSeconds);
  const distanceRatio = run.distanceMeters / mission.targetDistanceMeters;
  const distanceOk = distanceRatio >= missionRules.distanceTolerance;
  const paceOk = paceSecondsPerKm !== null && paceSecondsPerKm <= mission.maxPaceSecondsPerKm;
  const failReason = distanceOk && paceOk ? null
    : !distanceOk && !paceOk ? "both"
    : !distanceOk ? "distance_short" : "pace_too_slow";
  return {
    missionId: mission.id,
    outcome: failReason ? "failed" : "success",
    failReason,
    distanceMeters: run.distanceMeters,
    movingSeconds: run.movingSeconds,
    paceSecondsPerKm,
    distanceRatio: Math.round(distanceRatio * 1000) / 1000,
    ranTooFast: paceSecondsPerKm !== null && mission.minPaceSecondsPerKm !== null &&
      paceSecondsPerKm < mission.minPaceSecondsPerKm,
  };
}

/* ------------------------------------------------------------------ */
/* 난이도 조정                                                          */
/* ------------------------------------------------------------------ */

/**
 * 판정 결과를 훈련 상태에 반영해 다음 미션 난이도를 정합니다.
 * 반환한 trainingState를 저장하면 다음 recommendMissions에 반영됩니다.
 */
export function applyMissionResult({ trainingState, goal, mission, result }) {
  return result.outcome === "success"
    ? onSuccess(trainingState, goal, mission, result)
    : onFail(trainingState, goal, mission, result);
}

function onSuccess(s0, goal, mission, result) {
  const streak = s0.consecutiveSuccess + 1;
  let s = { ...s0, consecutiveSuccess: streak, consecutiveFail: 0, recoveryNext: false,
    completedMissionCount: s0.completedMissionCount + 1 };
  let nextHint;
  switch (mission.type) {
    case missionTypes.easy:
      s.easySuccessStreak = (s0.easySuccessStreak ?? 0) + 1;
      if (s.easySuccessStreak % 2 === 0) s.easyDistanceMeters += STEP;
      break;
    case missionTypes.long:
      s.longDistanceMeters = Math.max(s.longDistanceMeters + STEP, roundTo(s.longDistanceMeters * 1.1));
      break;
    case missionTypes.tempo:
      s.tempoPaceSecondsPerKm -= 3;
      if (streak % 3 === 0) s.tempoDistanceMeters += STEP;
      break;
    case missionTypes.goalPace:
      s.tempoPaceSecondsPerKm -= 2;
      nextHint = "목표 페이스에 한 걸음 더 가까워졌어요.";
      break;
    case missionTypes.goalChallenge:
      nextHint = "목표를 달성했어요! 새로운 목표를 설정해 보세요.";
      break;
  }
  s.easyPaceSecondsPerKm = Math.min(s.easyPaceSecondsPerKm, s.tempoPaceSecondsPerKm + 65);
  s = adjustStateForGoal(s, goal);
  if (mission.type === missionTypes.easy) {
    nextHint = s.easyDistanceMeters > s0.easyDistanceMeters
      ? `편안한 러닝 거리가 ${formatKm(s.easyDistanceMeters)}로 늘어나요.`
      : "이 흐름을 유지하면 편안한 러닝이 점점 더 쉬워져요.";
  }
  if (mission.type === missionTypes.long) nextHint = `다음 거리 늘리기 미션은 ${formatKm(s.longDistanceMeters)}예요.`;
  if (mission.type === missionTypes.tempo) nextHint = `다음 템포 러닝은 ${formatPace(s.tempoPaceSecondsPerKm)}/km로 조금 빨라져요.`;

  const pacePart = result.paceSecondsPerKm ? ` ${formatPace(result.paceSecondsPerKm)}/km로` : "";
  const fastPart = result.ranTooFast ? " 다만 권장보다 많이 빨랐어요. 무리하지 않게 페이스를 조절해 주세요." : "";
  const goalAchieved = mission.type === missionTypes.goalChallenge;
  return {
    trainingState: s,
    goalAchieved,
    feedback: {
      title: goalAchieved ? "목표 달성!" : "미션 성공!",
      message: `${formatKm(result.distanceMeters)}를${pacePart} 완주했어요.${fastPart}`,
      nextHint,
    },
  };
}

function onFail(s0, goal, mission, result) {
  const fails = s0.consecutiveFail + 1;
  let s = { ...s0, consecutiveFail: fails, consecutiveSuccess: 0, recoveryNext: fails >= 2, easySuccessStreak: 0,
    completedMissionCount: s0.completedMissionCount + 1 };
  const hints = [];

  if (result.failReason === "distance_short" || result.failReason === "both") {
    if (result.distanceRatio >= missionRules.retryThreshold) {
      hints.push("거의 다 왔어요. 같은 거리로 한 번 더 도전해요.");
    } else {
      const target = mission.targetDistanceMeters;
      const next = clamp(
        floorTo(result.distanceMeters),
        Math.max(MIN_DISTANCE, roundTo(target * missionRules.maxDecreaseRatio)),
        Math.max(MIN_DISTANCE, target - STEP),
      );
      if (mission.type === missionTypes.easy) s.easyDistanceMeters = next;
      else if (mission.type === missionTypes.long || mission.type === missionTypes.goalChallenge) {
        s.longDistanceMeters = Math.min(s.longDistanceMeters, next);
      } else s.tempoDistanceMeters = Math.min(s.tempoDistanceMeters, next);
      hints.push(`다음 미션 거리를 ${formatKm(next)}로 조정했어요.`);
    }
  }

  if (result.failReason === "pace_too_slow" || result.failReason === "both") {
    const slowBy = (result.paceSecondsPerKm ?? mission.maxPaceSecondsPerKm) - mission.maxPaceSecondsPerKm;
    const half = Math.trunc(slowBy / 2);
    if (mission.type === missionTypes.easy || mission.type === missionTypes.long) {
      s.easyPaceSecondsPerKm += clamp(half, 5, 15);
      hints.push(`편안한 러닝 기준 페이스를 ${formatPace(s.easyPaceSecondsPerKm)}/km로 조정했어요.`);
    } else {
      s.tempoPaceSecondsPerKm += clamp(half, 3, 10);
      hints.push(`다음 템포 페이스를 ${formatPace(s.tempoPaceSecondsPerKm)}/km로 완화했어요.`);
    }
  }
  if (s.recoveryNext) hints.push("다음에는 회복 러닝 하나만 준비할게요.");
  s = adjustStateForGoal(s, goal);

  const percent = Math.min(100, Math.round(result.distanceRatio * 100));
  const pacePart = result.paceSecondsPerKm ? `, 평균 ${formatPace(result.paceSecondsPerKm)}/km` : "";
  return {
    trainingState: s,
    goalAchieved: false,
    feedback: {
      title: "아쉽게 미달성",
      message: `목표 ${formatKm(mission.targetDistanceMeters)} 중 ${formatKm(result.distanceMeters)} (${percent}%)${pacePart}`,
      nextHint: hints.join(" "),
    },
  };
}

/* ------------------------------------------------------------------ */
/* 실시간 진행 (앱의 1초 갱신 루프와 같은 규칙. 앱은 android/MissionProgress.kt 사용) */
/* ------------------------------------------------------------------ */

const paceCritical = new Set([missionTypes.tempo, missionTypes.goalPace, missionTypes.goalChallenge]);

export function trackMission(mission, { distanceMeters, movingSeconds, gpsAvailable = true }) {
  const target = mission.targetDistanceMeters;
  const progress = Math.min(1, Math.max(0, distanceMeters / target));
  const remainingMeters = Math.max(0, Math.round(target - distanceMeters));
  const distanceReached = distanceMeters >= target * missionRules.distanceTolerance;
  const pace = distanceMeters < missionRules.paceWarmupMeters ? null : paceOf(distanceMeters, movingSeconds);
  const paceStatus = !gpsAvailable ? "gps_lost"
    : pace === null ? "measuring"
    : pace > mission.maxPaceSecondsPerKm ? "too_slow"
    : mission.minPaceSecondsPerKm !== null && pace < mission.minPaceSecondsPerKm ? "too_fast"
    : "on_target";
  let requiredPaceForRemaining = null;
  if (paceCritical.has(mission.type) && !distanceReached && remainingMeters > 0) {
    const left = mission.maxPaceSecondsPerKm * target / 1000 - movingSeconds;
    requiredPaceForRemaining = left <= 0 ? 0 : Math.round(left / (remainingMeters / 1000));
  }
  return { progress, remainingMeters, paceSecondsPerKm: pace, paceStatus, distanceReached, requiredPaceForRemaining };
}

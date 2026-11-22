export const assessmentRequirements = Object.freeze({
  minimumDistanceMeters: 3000,
  requiredRuns: 3,
});

export function summarizeAssessment(runs) {
  const { requiredRuns } = assessmentRequirements;
  const completedRuns = runs.length;
  const complete = completedRuns === requiredRuns;
  let baselinePaceSecondsPerKm = null;

  if (complete) {
    const totalDistanceMeters = runs.reduce((total, run) => total + run.distanceMeters, 0);
    const totalMovingSeconds = runs.reduce((total, run) => total + run.movingSeconds, 0);
    baselinePaceSecondsPerKm = Math.round(totalMovingSeconds / (totalDistanceMeters / 1000));
  }

  return {
    completedRuns,
    requiredRuns,
    status: complete ? "completed" : "in_progress",
    baselinePaceSecondsPerKm,
  };
}

const targetFieldByGoalType = new Map([
  ["distance", "targetDistanceMeters"],
  ["pace", "targetPaceSecondsPerKm"],
]);

export function normalizeGoal(input) {
  const type = input?.type;
  const field = targetFieldByGoalType.get(type);
  const target = input?.[field];
  if (!field || Object.keys(input).length !== 2 || !Number.isSafeInteger(target) || target <= 0) {
    return null;
  }
  return { type, [field]: target };
}

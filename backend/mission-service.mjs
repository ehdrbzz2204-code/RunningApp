import { randomUUID } from "node:crypto";
import { adjustStateForGoal, applyMissionResult, evaluateMission, recommendMissions } from "../mission/mission-logic.mjs";
import { assessmentRequirements, summarizeAssessment } from "./runner-profile.mjs";

export function createMissionService(db, storage) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS mission_attempts (
      id TEXT PRIMARY KEY NOT NULL,
      user_id TEXT NOT NULL,
      recommendation_key TEXT NOT NULL,
      mission TEXT NOT NULL,
      goal TEXT NOT NULL,
      initial_state TEXT NOT NULL,
      started INTEGER NOT NULL DEFAULT 0,
      run_id TEXT UNIQUE REFERENCES runs(id),
      outcome TEXT,
      UNIQUE (user_id, recommendation_key)
    ) STRICT;
    CREATE TABLE IF NOT EXISTS mission_training (
      user_id TEXT PRIMARY KEY NOT NULL,
      payload TEXT NOT NULL
    ) STRICT;
  `);
  const findTraining = db.prepare("SELECT payload FROM mission_training WHERE user_id = ?");
  const putTraining = db.prepare(`
    INSERT INTO mission_training (user_id, payload) VALUES (?, ?)
    ON CONFLICT (user_id) DO UPDATE SET payload = excluded.payload
  `);
  const offer = db.prepare(`
    INSERT INTO mission_attempts (id, user_id, recommendation_key, mission, goal, initial_state)
    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (user_id, recommendation_key) DO NOTHING
  `);
  const findOffer = db.prepare("SELECT mission FROM mission_attempts WHERE user_id = ? AND recommendation_key = ?");
  const findAttempt = db.prepare("SELECT * FROM mission_attempts WHERE user_id = ? AND id = ?");
  const start = db.prepare("UPDATE mission_attempts SET started = 1 WHERE user_id = ? AND id = ?");
  const finish = db.prepare("UPDATE mission_attempts SET run_id = ?, outcome = ? WHERE user_id = ? AND id = ?");

  function transaction(work) {
    db.exec("BEGIN IMMEDIATE");
    try {
      const result = work();
      db.exec("COMMIT");
      return result;
    } catch (error) {
      db.exec("ROLLBACK");
      throw error;
    }
  }

  function fail(status, code) {
    throw Object.assign(new Error(code), { missionError: { status, code } });
  }

  return {
    recommend(userId, date) {
      return transaction(() => {
        const assessment = summarizeAssessment(storage.getAssessmentRuns(userId, assessmentRequirements));
        const goal = storage.getActiveGoal(userId);
        const training = findTraining.get(userId);
        const recommendation = recommendMissions({
          assessment, goal, date, trainingState: training ? JSON.parse(training.payload) : null,
        });
        if (recommendation.status !== "ready") return recommendation;
        const { trainingState, missions, ...summary } = recommendation;
        return {
          ...summary,
          missions: missions.map((mission) => {
            const key = JSON.stringify([goal, mission]);
            const id = randomUUID();
            offer.run(id, userId, key, JSON.stringify({ ...mission, id }),
              JSON.stringify(goal), JSON.stringify(trainingState));
            return JSON.parse(findOffer.get(userId, key).mission);
          }),
        };
      });
    },
    start(userId, id) {
      return transaction(() => {
        const attempt = findAttempt.get(userId, id);
        if (!attempt) fail(404, "mission_not_found");
        if (attempt.run_id) fail(409, "mission_already_completed");
        start.run(userId, id);
        return JSON.parse(attempt.mission);
      });
    },
    saveRun(userId, run) {
      return transaction(() => {
        const saved = storage.saveRun(userId, run);
        if (!saved) fail(409, "client_run_id_conflict");

        const attempt = findAttempt.get(userId, run.missionId);
        if (!attempt) fail(404, "mission_not_found");
        if (!saved.created && attempt.run_id === saved.run.id) {
          return { ...saved, missionResult: JSON.parse(attempt.outcome) };
        }
        if (!attempt.started) fail(409, "mission_not_started");
        if (attempt.run_id) fail(409, "mission_already_completed");

        const mission = JSON.parse(attempt.mission);
        const goal = JSON.parse(attempt.goal);
        const result = evaluateMission(mission, run);
        const training = findTraining.get(userId);
        const trainingState = training
          ? adjustStateForGoal(JSON.parse(training.payload), goal)
          : JSON.parse(attempt.initial_state);
        const applied = applyMissionResult({ trainingState, goal, mission, result });
        const missionResult = { ...result, goalAchieved: applied.goalAchieved, feedback: applied.feedback };
        if (JSON.stringify(storage.getActiveGoal(userId)) === attempt.goal) {
          putTraining.run(userId, JSON.stringify(applied.trainingState));
        }
        finish.run(saved.run.id, JSON.stringify(missionResult), userId, run.missionId);
        return { ...saved, missionResult };
      });
    },
  };
}

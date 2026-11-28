import { randomUUID } from "node:crypto";

export function createStorage(db) {
  db.exec(`
    PRAGMA busy_timeout = 5000;
    CREATE TABLE IF NOT EXISTS runs (
      id TEXT PRIMARY KEY NOT NULL,
      user_id TEXT NOT NULL,
      client_run_id TEXT NOT NULL,
      payload TEXT NOT NULL,
      UNIQUE (user_id, client_run_id)
    ) STRICT;
    CREATE TABLE IF NOT EXISTS active_goals (
      user_id TEXT PRIMARY KEY NOT NULL,
      payload TEXT NOT NULL
    ) STRICT;
  `);

  const insertRun = db.prepare(`
    INSERT INTO runs (id, user_id, client_run_id, payload) VALUES (?, ?, ?, ?)
    ON CONFLICT (user_id, client_run_id) DO NOTHING
  `);
  const findRun = db.prepare("SELECT id, payload FROM runs WHERE user_id = ? AND client_run_id = ?");
  const findAssessmentRuns = db.prepare(`
    SELECT json_extract(payload, '$.distanceMeters') AS distanceMeters,
           json_extract(payload, '$.movingSeconds') AS movingSeconds
    FROM runs
    WHERE user_id = ?
      AND json_extract(payload, '$.type') = 'assessment'
      AND json_extract(payload, '$.distanceMeters') >= ?
    ORDER BY rowid
    LIMIT ?
  `);
  const findGoal = db.prepare("SELECT payload FROM active_goals WHERE user_id = ?");
  const upsertGoal = db.prepare(`
    INSERT INTO active_goals (user_id, payload) VALUES (?, ?)
    ON CONFLICT (user_id) DO UPDATE SET payload = excluded.payload
  `);

  return {
    saveRun(userId, run) {
      const payload = JSON.stringify(run);
      const result = insertRun.run(randomUUID(), userId, run.clientRunId, payload);
      const saved = findRun.get(userId, run.clientRunId);
      if (saved.payload !== payload) return null;
      return { created: result.changes > 0, run: { id: saved.id, ...run } };
    },
    getAssessmentRuns(userId, { minimumDistanceMeters, requiredRuns }) {
      return findAssessmentRuns.all(userId, minimumDistanceMeters, requiredRuns);
    },
    getActiveGoal(userId) {
      const saved = findGoal.get(userId);
      return saved ? JSON.parse(saved.payload) : null;
    },
    putActiveGoal(userId, goal) {
      upsertGoal.run(userId, JSON.stringify(goal));
    },
  };
}

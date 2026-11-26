const sortTime = "COALESCE(json_extract(runs.payload, '$.finishedAt'), '')";
const runIdPattern = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;

export function normalizeFinishedAt(value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) {
    return null;
  }
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return null;
  const expected = value.length === 20 ? value.replace("Z", ".000Z") : value;
  return date.toISOString() === expected ? expected : null;
}

export function readHistoryQuery(search) {
  const query = new URLSearchParams(search);
  const limit = query.get("limit") ?? "20";
  const cursor = query.get("cursor");
  if (!/^(?:[1-9]\d?|100)$/.test(limit) || (cursor !== null && !runIdPattern.test(cursor)) ||
      query.getAll("limit").length > 1 || query.getAll("cursor").length > 1) {
    return null;
  }
  return { limit: Number(limit), cursor };
}

export function createRunHistory(db) {
  db.exec(`
    CREATE INDEX IF NOT EXISTS runs_history_order
    ON runs(user_id, COALESCE(json_extract(payload, '$.finishedAt'), '') DESC, id DESC)
  `);
  const findCursor = db.prepare(`
    SELECT ${sortTime} AS sortTime FROM runs WHERE user_id = ? AND id = ?
  `);
  const select = `
    SELECT runs.id, runs.payload, mission_attempts.outcome
    FROM runs
    LEFT JOIN mission_attempts ON mission_attempts.run_id = runs.id
      AND mission_attempts.user_id = runs.user_id
    WHERE runs.user_id = ?
  `;
  const order = `ORDER BY ${sortTime} DESC, runs.id DESC LIMIT ?`;
  const firstPage = db.prepare(`${select} ${order}`);
  const nextPage = db.prepare(`${select} AND (${sortTime}, runs.id) < (?, ?) ${order}`);

  return (userId, { limit, cursor }) => {
    const anchor = cursor === null ? null : findCursor.get(userId, cursor);
    if (cursor !== null && !anchor) return null;

    const rows = anchor
      ? nextPage.all(userId, anchor.sortTime, cursor, limit + 1)
      : firstPage.all(userId, limit + 1);
    const items = rows.slice(0, limit).map(({ id, payload, outcome }) => {
      const run = JSON.parse(payload);
      return {
        id,
        ...run,
        finishedAt: run.finishedAt ?? null,
        averagePaceSecondsPerKm: run.distanceMeters > 0
          ? Math.round(run.movingSeconds / (run.distanceMeters / 1000))
          : null,
        missionResult: outcome === null ? null : JSON.parse(outcome),
      };
    });
    return { items, nextCursor: rows.length > limit ? items.at(-1).id : null };
  };
}

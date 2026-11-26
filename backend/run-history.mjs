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

const koreaOffsetMs = 9 * 60 * 60 * 1000;

export function readStatsQuery(search, now = Date.now()) {
  const query = new URLSearchParams(search);
  const period = query.get("period");
  const date = query.get("date") ?? new Date(now + koreaOffsetMs).toISOString().slice(0, 10);
  if ((period !== "week" && period !== "month") || !/^\d{4}-\d{2}-\d{2}$/.test(date) ||
      query.getAll("period").length !== 1 || query.getAll("date").length > 1) return null;

  const midnight = normalizeFinishedAt(date + "T00:00:00Z");
  if (midnight === null) return null;
  const start = new Date(midnight);
  if (period === "week") {
    start.setUTCDate(start.getUTCDate() - (start.getUTCDay() + 6) % 7);
  } else {
    start.setUTCDate(1);
  }
  const end = new Date(start);
  if (period === "week") {
    end.setUTCDate(end.getUTCDate() + 7);
  } else {
    end.setUTCMonth(end.getUTCMonth() + 1);
  }

  const from = new Date(start.getTime() - koreaOffsetMs).toISOString();
  const to = new Date(end.getTime() - koreaOffsetMs).toISOString();
  if (start.toISOString().length !== 24 || end.toISOString().length !== 24 ||
      from.length !== 24 || to.length !== 24) return null;
  return {
    period,
    timeZone: "Asia/Seoul",
    startDate: start.toISOString().slice(0, 10),
    endDateExclusive: end.toISOString().slice(0, 10),
    from,
    to,
  };
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
  const summarize = db.prepare(`
    SELECT count(*) AS runCount,
           total(json_extract(payload, '$.distanceMeters')) AS totalDistanceMeters,
           total(json_extract(payload, '$.movingSeconds')) AS totalMovingSeconds
    FROM runs
    WHERE user_id = ? AND ${sortTime} >= ? AND ${sortTime} < ?
  `);

  function list(userId, { limit, cursor }) {
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
  }

  function stats(userId, { from, to, ...period }) {
    const totals = summarize.get(userId, from, to);
    const { totalDistanceMeters, totalMovingSeconds } = totals;
    return {
      ...period,
      ...totals,
      averagePaceSecondsPerKm: totalDistanceMeters > 0
        ? Math.round(totalMovingSeconds / (totalDistanceMeters / 1000))
        : null,
    };
  }

  return { list, stats };
}

import { createServer } from "node:http";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { randomUUID } from "node:crypto";

const tokens = new Map(Object.entries(JSON.parse(process.env.RUN_API_TOKENS ?? "{}")));
if (!tokens.size || [...tokens].some(([token, user]) =>
  token.length < 32 || typeof user !== "string" || !user.trim())) {
  throw new Error("Set RUN_API_TOKENS to a JSON object mapping random tokens of at least 32 characters to user IDs.");
}

const databasePath = resolve(process.env.RUN_DB_PATH ?? "backend/data/runs.db");
mkdirSync(dirname(databasePath), { recursive: true });
const db = new DatabaseSync(databasePath);
db.exec(`
  PRAGMA busy_timeout = 5000;
  CREATE TABLE IF NOT EXISTS runs (
    id TEXT PRIMARY KEY NOT NULL,
    user_id TEXT NOT NULL,
    client_run_id TEXT NOT NULL,
    payload TEXT NOT NULL,
    UNIQUE (user_id, client_run_id)
  ) STRICT;
`);
const insert = db.prepare(`
  INSERT INTO runs (id, user_id, client_run_id, payload) VALUES (?, ?, ?, ?)
  ON CONFLICT (user_id, client_run_id) DO NOTHING
`);
const find = db.prepare("SELECT id, payload FROM runs WHERE user_id = ? AND client_run_id = ?");

function reply(response, status, body) {
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(body));
}

async function parseRun(request) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request.iterator({ destroyOnReturn: false })) {
    size += chunk.length;
    if (size > 8192) {
      request.resume();
      throw Object.assign(new Error("payload_too_large"), { status: 413 });
    }
    chunks.push(chunk);
  }
  const { clientRunId, distanceMeters, movingSeconds, type, missionId = null } =
    JSON.parse(Buffer.concat(chunks).toString("utf8"));
  const validId = (value) => typeof value === "string" && /^[a-zA-Z0-9_-]{1,128}$/.test(value);
  if (!validId(clientRunId) ||
      !Number.isSafeInteger(distanceMeters) || distanceMeters < 0 ||
      !Number.isSafeInteger(movingSeconds) || movingSeconds <= 0 ||
      !["free", "assessment"].includes(type) || missionId !== null) {
    throw new Error("invalid_run");
  }
  return { clientRunId, distanceMeters, movingSeconds, type, missionId };
}

const server = createServer(async (request, response) => {
  if (request.method !== "POST" || request.url !== "/runs") {
    reply(response, 404, { error: "not_found" });
    return;
  }
  const token = request.headers.authorization?.match(/^Bearer ([^\s]+)$/i)?.[1];
  const userId = tokens.get(token);
  if (!userId) {
    reply(response, 401, { error: "unauthorized" });
    return;
  }
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers["content-type"] ?? "")) {
    reply(response, 415, { error: "expected_json" });
    return;
  }

  let run;
  try {
    run = await parseRun(request);
  } catch (error) {
    reply(response, error.status ?? 400, {
      error: error.status === 413 ? "payload_too_large" : "invalid_run",
    });
    return;
  }

  try {
    const payload = JSON.stringify(run);
    const result = insert.run(randomUUID(), userId, run.clientRunId, payload);
    const saved = find.get(userId, run.clientRunId);
    if (saved.payload !== payload) {
      reply(response, 409, { error: "client_run_id_conflict" });
      return;
    }
    reply(response, result.changes ? 201 : 200, { id: saved.id, ...run });
  } catch (error) {
    console.error("Failed to save run:", error.code);
    reply(response, 500, { error: "storage_error" });
  }
});

server.listen(Number(process.env.PORT ?? 3000), "127.0.0.1", () => {
  console.log("RunningApp API listening on port", server.address().port);
});

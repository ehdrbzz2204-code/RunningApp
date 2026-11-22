import { createServer } from "node:http";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { assessmentRequirements, normalizeGoal, summarizeAssessment } from "./runner-profile.mjs";
import { createStorage } from "./storage.mjs";

const tokens = new Map(Object.entries(JSON.parse(process.env.RUN_API_TOKENS ?? "{}")));
if (!tokens.size || [...tokens].some(([token, user]) =>
  token.length < 32 || typeof user !== "string" || !user.trim())) {
  throw new Error("Set RUN_API_TOKENS to a JSON object mapping random tokens of at least 32 characters to user IDs.");
}

const databasePath = resolve(process.env.RUN_DB_PATH ?? "backend/data/runs.db");
mkdirSync(dirname(databasePath), { recursive: true });
const storage = createStorage(new DatabaseSync(databasePath));

function reply(response, status, body) {
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(body));
}

async function readJson(request) {
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
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function normalizeRun(input) {
  const { clientRunId, distanceMeters, movingSeconds, type, missionId = null } = input ?? {};
  const validId = (value) => typeof value === "string" && /^[a-zA-Z0-9_-]{1,128}$/.test(value);
  if (!validId(clientRunId) ||
      !Number.isSafeInteger(distanceMeters) || distanceMeters < 0 ||
      !Number.isSafeInteger(movingSeconds) || movingSeconds <= 0 ||
      !["free", "assessment"].includes(type) || missionId !== null) {
    return null;
  }
  return { clientRunId, distanceMeters, movingSeconds, type, missionId };
}

function withJsonBody(normalize, invalidError, handle) {
  return async (request, response, userId) => {
    if (!/^application\/json(?:\s*;|$)/i.test(request.headers["content-type"] ?? "")) {
      reply(response, 415, { error: "expected_json" });
      return;
    }

    let body;
    try {
      body = normalize(await readJson(request));
    } catch (error) {
      reply(response, error.status ?? 400, {
        error: error.status === 413 ? "payload_too_large" : invalidError,
      });
      return;
    }
    if (!body) {
      reply(response, 400, { error: invalidError });
      return;
    }
    handle(response, userId, body);
  };
}

const routes = new Map([
  ["POST /runs", withJsonBody(normalizeRun, "invalid_run", (response, userId, run) => {
    const saved = storage.saveRun(userId, run);
    if (!saved) {
      reply(response, 409, { error: "client_run_id_conflict" });
      return;
    }
    reply(response, saved.created ? 201 : 200, saved.run);
  })],
  ["GET /assessment", (_request, response, userId) => {
    const runs = storage.getAssessmentRuns(userId, assessmentRequirements);
    reply(response, 200, summarizeAssessment(runs));
  }],
  ["GET /goals/active", (_request, response, userId) => {
    reply(response, 200, { goal: storage.getActiveGoal(userId) });
  }],
  ["PUT /goals/active", withJsonBody(normalizeGoal, "invalid_goal", (response, userId, goal) => {
    storage.putActiveGoal(userId, goal);
    reply(response, 200, { goal });
  })],
]);

const server = createServer(async (request, response) => {
  const handle = routes.get(`${request.method} ${request.url}`);
  if (!handle) {
    reply(response, 404, { error: "not_found" });
    return;
  }
  const token = request.headers.authorization?.match(/^Bearer ([^\s]+)$/i)?.[1];
  const userId = tokens.get(token);
  if (!userId) {
    reply(response, 401, { error: "unauthorized" });
    return;
  }
  try {
    await handle(request, response, userId);
  } catch (error) {
    console.error("Failed to handle request:", error.code);
    reply(response, 500, { error: "storage_error" });
  }
});

server.listen(Number(process.env.PORT ?? 3000), "127.0.0.1", () => {
  console.log("RunningApp API listening on port", server.address().port);
});
